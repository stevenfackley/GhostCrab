import Foundation
import SwiftUI

/// A dashboard entry that opens a screen needing a session capability (ADR-0007): the screen's RPCs fail with a
/// missing scope unless `hello-ok` granted it and the gateway offers the methods.
public enum DashboardFeature: CaseIterable, Sendable {
    /// The config editor: `config.get` + `config.patch`, `operator.admin`.
    case config
    /// The model manager: `models.list`, `operator.read`.
    case models
    /// Installed skills: `skills.status`, `operator.read`.
    case skills

    /// The ``GatewayHello`` capability key the entry needs.
    var capability: String {
        switch self {
        case .config: return GatewayHello.capabilityConfig
        case .models: return GatewayHello.capabilityModels
        case .skills: return GatewayHello.capabilitySkills
        }
    }

    /// Why the entry is disabled on a live session that lacks ``capability``.
    var missingCapabilityReason: String {
        switch self {
        case .config:
            return "Needs the operator.admin scope and a gateway that offers config.get and config.patch."
        case .models:
            return "Needs the operator.read scope and a gateway that offers models.list."
        case .skills:
            return "Needs the operator.read scope and a gateway that offers skills.status."
        }
    }
}

/// Drives the connected-gateway dashboard.
///
/// Subscribes to `GatewayConnectionManager.connectionStates()` and tracks the
/// latest connection, the active model, and a list of recent telemetry events
/// (always empty in v1 — WebSocket streaming is out of scope per the project
/// charter).
///
/// Mirrors `DashboardViewModel.kt`, simplified for the iOS port: the periodic
/// `/health` polling and `Degraded` state are not part of v1 — the
/// `ConnectionStatusBar` at the top of the screen is the canonical liveness
/// indicator, and pull-to-refresh reloads the model list on demand.
///
/// A silent reconnect after a dropped socket changes nothing on screen (no reload, no error); the reconnected session
/// (a new `sessionGeneration`) reloads the dashboard. When the reconnect fails the state is `.error`: the screen shows
/// the typed end cause (``endCause``) and a Retry (``retry()``). The warnings are re-read on every state change.
@MainActor
@Observable
public final class DashboardViewModel {

    // MARK: - Dependencies

    private let gateway: any GatewayConnectionManager
    private let models: any ModelRepository
    private let ai: any AIRecommendationService

    // MARK: - Observable state

    /// Latest connection snapshot. Mirrors the Kotlin `state.connection` field.
    public private(set) var connection: GatewayConnection = .disconnected

    /// Currently active model, or `nil` if none / not yet loaded.
    public private(set) var activeModel: ModelInfo?

    /// Total number of models known to the gateway. Drives the "x models" subtitle.
    public private(set) var modelCount: Int = 0

    /// Whether the gateway exposes the AI recommendation skill — gates the AI tile.
    /// Resolved lazily on appear via `ai.isAvailable()`.
    public private(set) var aiAvailable: Bool = false

    /// Recent telemetry. Always empty in v1 — kept on the VM so the screen can
    /// render an empty section without conditional plumbing.
    public private(set) var recentTelemetry: [String] = []

    /// True while a refresh / initial load is in flight.
    public private(set) var isLoading: Bool = false

    /// Last error surfaced to the user (URL + status code + exception class
    /// when available). `nil` when no error is pending.
    public var lastError: String?

    /// `true` once the user asked to disconnect; repeat taps are ignored.
    public private(set) var isDisconnecting: Bool = false

    /// Problems that did not fail the live session (``GatewayConnectionManager/connectionWarnings()``), e.g.
    /// ``ConnectionWarning/deviceTokenNotSaved`` after an approval: the session works, but later connects need the
    /// gateway token. Shown amber; empty when there is nothing to report. Re-read on every connection state change.
    public private(set) var warnings: [ConnectionWarning] = []

    /// `true` while ``retry()`` connects again.
    public private(set) var isRetrying: Bool = false

    /// Why the session ended, while the state is `.error` (e.g. the socket dropped and the silent reconnect failed):
    /// the error's case and its message with the URL, e.g. `unreachable: Gateway unreachable at ws://…/ws`.
    /// `nil` in every other state.
    public var endCause: String? {
        guard case let .error(_, cause) = connection else { return nil }
        return Self.describeEndCause(cause)
    }

    // MARK: - Internal tasks

    @ObservationIgnored
    private var connectionObservationTask: Task<Void, Never>?

    /// In-flight model load from `onAppear`. Read-only outside the VM so tests can await it.
    @ObservationIgnored private(set) var loadTask: Task<Void, Never>?

    /// In-flight AI availability probe. Read-only outside the VM so tests can await it.
    @ObservationIgnored private(set) var aiTask: Task<Void, Never>?

    /// The session the model card was loaded for.
    @ObservationIgnored private var sessions = SessionTracker()

    /// URL and token of the latest live session, for ``retry()``. The token is sensitive: never shown or logged.
    @ObservationIgnored private var lastSession: (url: String, token: String?)?

    // MARK: - Init

    public init(
        gateway: any GatewayConnectionManager,
        models: any ModelRepository,
        ai: any AIRecommendationService
    ) {
        self.gateway = gateway
        self.models = models
        self.ai = ai
        self.connection = gateway.currentState
    }

    deinit {
        connectionObservationTask?.cancel()
    }

    // MARK: - Lifecycle

    /// Call from the screen's `.task` modifier. Starts observing the connection
    /// stream, loads the active model, and probes AI availability. Loads that
    /// are already running are not started again.
    public func onAppear() {
        if loadTask == nil {
            sessions.markLoaded(gateway.currentState)
            startLoad()
        }
        startObservingConnection()
        if aiTask == nil {
            aiTask = Task {
                await self.probeAIAvailability()
                if !Task.isCancelled { self.aiTask = nil }
            }
        }
    }

    /// Call from the screen's `.onDisappear` (e.g. pushing Models / Config).
    /// Cancels in-flight work; `onAppear()` restarts it on return.
    public func onDisappear() {
        connectionObservationTask?.cancel()
        connectionObservationTask = nil
        loadTask?.cancel()
        loadTask = nil
        aiTask?.cancel()
        aiTask = nil
    }

    /// Manual refresh — invoked by `.refreshable`. Reloads the model list.
    public func refresh() async {
        await loadWarnings()
        await loadActiveModel()
    }

    /// Connects again to the gateway whose session ended, with the token that session used (after a dropped socket the
    /// silent reconnect could not repair). No-op unless the state is `.error`, and while a retry runs. The outcome
    /// arrives through the connection state: a new session reloads the dashboard; a failure shows its cause.
    public func retry() async {
        guard !isRetrying, case let .error(url, _) = connection else { return }
        isRetrying = true
        defer { isRetrying = false }
        let token = lastSession?.url == url ? lastSession?.token : nil
        do {
            try await gateway.connect(url: url, token: token)
        } catch is CancellationError {
            // Superseded by a newer connect or disconnect: that call owns the state.
        } catch {
            // `connect` has already published `.error` with this cause; ``endCause`` shows it from the state.
        }
    }

    /// - Parameter feature: A dashboard entry.
    /// - Returns: `nil` when the entry's screen can be opened on this session, else a plain reason it is disabled:
    ///   not connected, or the session's `hello-ok` scopes (or the gateway's methods) do not allow it. A silent
    ///   reconnect keeps the lost session's capabilities until the new session replaces them.
    public func unavailableReason(_ feature: DashboardFeature) -> String? {
        guard case .connected = connection else { return "Not connected to a gateway." }
        return connection.sessionCapabilities.contains(feature.capability) ? nil : feature.missingCapabilityReason
    }

    /// Disconnects from the gateway once. The caller pops the nav stack only
    /// when this returns `true`, so a double tap cannot dismiss twice.
    ///
    /// - Returns: `true` for the call that disconnected; `false` for repeats.
    public func disconnect() async -> Bool {
        guard !isDisconnecting else { return false }
        isDisconnecting = true
        await gateway.disconnect()
        return true
    }

    // MARK: - Connection observation

    private func startObservingConnection() {
        connectionObservationTask?.cancel()
        let stream = gateway.connectionStates()
        // Task inherits @MainActor from the enclosing class — direct property
        // writes inside `for await` are MainActor-isolated by construction.
        connectionObservationTask = Task { [weak self] in
            for await state in stream {
                guard let self else { return }
                self.connection = state
                if case let .connected(url, _, _, _, _, _, _, token, false, _) = state {
                    self.lastSession = (url: url, token: token)
                }
                if self.sessions.isNewSession(state) {
                    // A new session (after a reconnect or a Retry); the reconnecting state itself is not one.
                    self.loadTask?.cancel()
                    self.startLoad()
                }
                await self.loadWarnings()
                if case .disconnected = state {
                    return
                }
            }
        }
    }

    private func startLoad() {
        loadTask = Task {
            await self.loadWarnings()
            await self.loadActiveModel()
            if !Task.isCancelled { self.loadTask = nil }
        }
    }

    // MARK: - Data loaders

    private func loadActiveModel() async {
        isLoading = true
        defer { isLoading = false }
        do {
            let list = try await models.getModels()
            guard !Task.isCancelled else { return }
            self.modelCount = list.count
            self.activeModel = list.first(where: { $0.isActive })
            self.lastError = nil
        } catch {
            // A cancelled load (left the screen) is not an error worth a banner:
            // URLSession reports cancellation as `URLError.cancelled`, which the
            // client maps to `.unreachable`.
            guard !Task.isCancelled else { return }
            if let gatewayError = error as? GatewayError {
                self.lastError = describe(error: gatewayError)
            } else {
                self.lastError = "\(type(of: error)): \(error.localizedDescription)"
            }
        }
    }

    private func loadWarnings() async {
        let current = await gateway.connectionWarnings()
        guard !Task.isCancelled else { return }
        self.warnings = current
    }

    private func probeAIAvailability() async {
        let available = await ai.isAvailable()
        guard !Task.isCancelled else { return }
        self.aiAvailable = available
    }

    // MARK: - Helpers

    /// Format an error into the explicit URL + status + exception-class form
    /// the brand guide requires. Falls back to the localized description when
    /// the structured form isn't available.
    private func describe(error: GatewayError) -> String {
        let typeName = String(describing: type(of: error))
        return "\(typeName): \(error.localizedDescription)"
    }

    /// - Returns: `<case>: <message with the URL>`, e.g. `connectionClosed: Gateway at ws://…/ws closed the
    ///   connection: WebSocket 1008`.
    static func describeEndCause(_ error: GatewayError) -> String {
        let caseName = Mirror(reflecting: error).children.first?.label ?? String(describing: error)
        return "\(caseName): \(error.errorDescription ?? String(describing: error))"
    }
}
