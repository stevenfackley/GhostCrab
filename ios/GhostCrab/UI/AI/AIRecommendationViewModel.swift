import Foundation
import Observation

/// ViewModel for the AI Recommendations screen.
///
/// Mirrors the Android `AIRecommendationViewModel` but trimmed to the wave-4-leaf
/// scope: free-text prompt → response, with an optional suggested config patch
/// that the user can confirm via a sheet. ``checkAvailability()`` asks the
/// service first: OpenClaw gateways have no recommendation API (ADR-0007), so
/// today ``available`` is `false` and the screen says so instead of offering a
/// prompt.
@MainActor
@Observable
public final class AIRecommendationViewModel {

    // MARK: - Public state

    /// The current prompt text. Bound to the screen's `TextField`.
    public var prompt: String = ""

    /// `true` while a `submit()` request is in flight.
    public private(set) var loading: Bool = false

    /// Latest successful response, or `nil` if no query has been answered yet.
    public private(set) var response: AIRecommendation?

    /// Most recent error to surface. `nil` once cleared.
    public private(set) var lastError: AIRecommendationError?

    /// Whether the screen should present the "apply suggested patch" sheet.
    public var isPresentingPatch: Bool = false

    /// Whether the gateway serves recommendations: `nil` until
    /// ``checkAvailability()`` finished, `false` for every OpenClaw gateway
    /// today (ADR-0007).
    public private(set) var available: Bool?

    /// In-flight availability check. Read-only outside the VM so tests can await it.
    @ObservationIgnored private(set) var availabilityTask: Task<Void, Never>?

    /// In-flight recommendation request. Read-only outside the VM so tests can await it.
    @ObservationIgnored private(set) var submitTask: Task<Void, Never>?

    /// In-flight patch write. Read-only outside the VM so tests can await it.
    @ObservationIgnored private(set) var applyTask: Task<Void, Never>?

    // MARK: - Dependencies

    private let ai: any AIRecommendationService
    private let gateway: any GatewayConnectionManager
    private let config: any ConfigRepository
    private let models: any ModelRepository

    // MARK: - Init

    public init(
        ai: any AIRecommendationService,
        gateway: any GatewayConnectionManager,
        config: any ConfigRepository,
        models: any ModelRepository
    ) {
        self.ai = ai
        self.gateway = gateway
        self.config = config
        self.models = models
    }

    // MARK: - Public API

    /// Asks the service whether the gateway serves recommendations and
    /// publishes the answer in ``available``. Ignored while a check is in
    /// flight or once it has finished.
    public func checkAvailability() {
        guard availabilityTask == nil, available == nil else { return }
        availabilityTask = Task { [weak self, ai] in
            let answer = await ai.isAvailable()
            guard let self, !Task.isCancelled else { return }
            self.available = answer
            self.availabilityTask = nil
        }
    }

    /// Submits ``prompt`` to the gateway's recommendation service.
    ///
    /// Auto-collects ``RecommendationContext`` from the current connection,
    /// installed config, and active model. Network failures for context loaders
    /// are non-fatal — the request proceeds with empty fallbacks rather than
    /// being aborted (mirrors Android `buildContext`).
    public func submit() {
        let trimmed = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        guard !loading else { return }

        loading = true
        lastError = nil

        submitTask = Task { [weak self] in
            guard let self else { return }
            // A cancelled task leaves `submitTask` alone: `cancelInFlight()` already cleared it.
            defer { if !Task.isCancelled { self.submitTask = nil } }
            let context = await self.buildContext()
            do {
                let result = try await self.ai.getRecommendation(query: trimmed, context: context)
                guard !Task.isCancelled else { return }
                self.response = result
                self.loading = false
            } catch let error as GatewayError {
                guard !Task.isCancelled else { return }
                self.lastError = AIRecommendationError(gatewayError: error)
                self.response = nil
                self.loading = false
            } catch {
                guard !Task.isCancelled else { return }
                self.lastError = .other(message: error.localizedDescription)
                self.response = nil
                self.loading = false
            }
        }
    }

    /// Returns `true` if the latest response carries a non-empty suggested patch.
    public var hasSuggestedPatch: Bool {
        (response?.suggestedChanges.isEmpty ?? true) == false
    }

    /// Opens the "apply suggested config" sheet.
    public func applySuggestedPatch() {
        guard hasSuggestedPatch else { return }
        isPresentingPatch = true
    }

    /// Persists all suggested changes from the latest response back to the
    /// gateway via ``ConfigRepository/updateConfig(section:value:)``.
    ///
    /// On success: closes the patch sheet and leaves the response on screen
    /// for context. On any failure: surfaces ``lastError`` and keeps the sheet open
    /// so the user can retry. Ignored while a write is already in flight.
    public func confirmApplyPatch() {
        guard applyTask == nil else { return }
        guard let response, !response.suggestedChanges.isEmpty else {
            isPresentingPatch = false
            return
        }
        applyTask = Task { [weak self, config] in
            guard let self else { return }
            defer { self.applyTask = nil }
            do {
                for change in response.suggestedChanges {
                    // Build a JSON merge-patch: `{ <key>: <suggestedValue> }`.
                    let patch = AnyCodable([change.key: AnyCodable(Self.parseJSONValue(change.suggestedValue))])
                    try await config.updateConfig(section: change.section, value: patch)
                }
                self.isPresentingPatch = false
            } catch let error as GatewayError {
                self.lastError = AIRecommendationError(gatewayError: error)
            } catch {
                self.lastError = .other(message: error.localizedDescription)
            }
        }
    }

    /// Cancels an in-flight recommendation when the screen goes away. Patch
    /// writes are left to finish; aborting between sections would leave the
    /// config half-applied.
    public func cancelInFlight() {
        submitTask?.cancel()
        submitTask = nil
        loading = false
    }

    /// Clears the error banner after the view has shown it.
    public func dismissError() {
        lastError = nil
    }

    /// Returns the response text rendered as `AttributedString` parsed as Markdown.
    /// Falls back to a plain `AttributedString` when parsing fails.
    public func attributedResponse() -> AttributedString {
        guard let response else { return AttributedString("") }
        if let parsed = try? AttributedString(markdown: response.recommendation,
                                              options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace)) {
            return parsed
        }
        return AttributedString(response.recommendation)
    }

    // MARK: - Internals

    /// Assembles ``RecommendationContext`` from the active gateway connection,
    /// `/config`, and `/models`. Failures for config/model are non-fatal.
    private func buildContext() async -> RecommendationContext {
        let conn = gateway.currentState
        var hardwareInfo: String?
        if case .connected(_, _, _, _, _, _, let hw, _, _, _) = conn {
            hardwareInfo = hw
        }

        let activeConfig: OpenClawConfig = await {
            do { return try await config.getConfig() }
            catch { return OpenClawConfig(sections: [:]) }
        }()

        let activeModelId: String? = await {
            do { return try await models.getModels().first(where: { $0.isActive })?.id }
            catch { return nil }
        }()

        return RecommendationContext(
            activeConfig: activeConfig,
            hardwareInfo: hardwareInfo,
            activeModelId: activeModelId
        )
    }

    /// Parses `value` as a JSON literal so that `"42"` becomes an `Int`, `"true"`
    /// becomes a `Bool`, etc. Falls back to the raw string when parsing fails so
    /// malformed suggestions still apply.
    private static func parseJSONValue(_ value: String) -> (any Sendable)? {
        guard let data = value.data(using: .utf8) else { return value }
        if let decoded = try? JSONDecoder().decode(AnyCodable.self, from: data) {
            return decoded.value
        }
        return value
    }
}

// MARK: - View-facing error

/// View-facing error shape for the AI recommendation screen.
///
/// Direct port of the Android `Error` / `SkillUnavailable` distinction. No case
/// offers an install: no skill adds recommendations to an OpenClaw gateway
/// (ADR-0007).
public enum AIRecommendationError: Sendable, Equatable {
    /// The gateway serves no recommendations (every OpenClaw gateway today, ADR-0007).
    case skillUnavailable(url: String)
    /// The AI rate limit was exceeded.
    case quotaExceeded(url: String)
    /// Generic gateway-side error (network, auth, API).
    case other(message: String)

    /// Headline shown wherever recommendations are unavailable.
    public static let unavailableTitle = "AI recommendations unavailable"

    /// Why recommendations are unavailable, and what would bring them back (same copy as Android).
    public static let unavailableBody = "OpenClaw gateways have no recommendation API, and no skill adds one. "
        + "GhostCrab will offer recommendations again once they can run as an agent turn on your gateway (chat.send)."

    public init(gatewayError: GatewayError) {
        switch gatewayError {
        case .aiServiceUnavailable(let url):
            self = .skillUnavailable(url: url)
        case .aiQuotaExceeded(let url):
            self = .quotaExceeded(url: url)
        default:
            self = .other(message: gatewayError.errorDescription ?? "Unknown gateway error")
        }
    }

    public var headline: String {
        switch self {
        case .skillUnavailable:
            return AIRecommendationError.unavailableTitle
        case .quotaExceeded:
            return "AI rate limit exceeded"
        case .other:
            return "AI request failed"
        }
    }

    public var body: String {
        switch self {
        case .skillUnavailable(let url):
            return "The gateway at \(url) serves no recommendations. \(AIRecommendationError.unavailableBody)"
        case .quotaExceeded(let url):
            return "Gateway at \(url) rate-limited the request. Wait a few seconds before retrying."
        case .other(let message):
            return message
        }
    }
}
