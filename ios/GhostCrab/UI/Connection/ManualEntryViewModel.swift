import Foundation
import Observation

/// ViewModel for the manual gateway URL/token entry screen.
///
/// Mirrors `ManualEntryViewModel.kt`. Holds a form state with per-field validation,
/// drives the connect flow, persists the profile + token, marks onboarding completed,
/// then signals the host to navigate to `.dashboard`.
@MainActor
@Observable
public final class ManualEntryViewModel {

    // MARK: - Constants

    /// Default OpenClaw Gateway port (mirrors `DEFAULT_GATEWAY_PORT`).
    public static let defaultPort: String = "18789"

    // MARK: - Dependencies

    private let connectionManager: any GatewayConnectionManager
    private let profileRepository: any ConnectionProfileRepository
    private let onboardingRepository: any OnboardingRepository

    // MARK: - Form state

    public var useHttps: Bool = false
    public var host: String = ""
    public var port: String = ManualEntryViewModel.defaultPort
    public var token: String = ""
    public var tokenVisible: Bool = false

    public private(set) var hostError: String?
    public private(set) var portError: String?

    public private(set) var isConnecting: Bool = false
    public var lastError: String?

    /// Flipped to `true` after a successful connect + profile save; host pushes `.dashboard`.
    public private(set) var navigateToDashboard: Bool = false

    // MARK: - Init

    public init(
        connectionManager: any GatewayConnectionManager,
        profileRepository: any ConnectionProfileRepository,
        onboardingRepository: any OnboardingRepository
    ) {
        self.connectionManager = connectionManager
        self.profileRepository = profileRepository
        self.onboardingRepository = onboardingRepository
    }

    // MARK: - Derived

    public var scheme: String { useHttps ? "https" : "http" }

    /// `scheme://host:port` — exact URL that will be hit, shown beneath the inputs.
    public var assembledURL: String {
        "\(scheme)://\(host.trimmingCharacters(in: .whitespaces)):\(port.trimmingCharacters(in: .whitespaces))"
    }

    // MARK: - Field setters

    public func setHost(_ value: String) {
        self.host = value
        self.hostError = nil
    }

    public func setPort(_ value: String) {
        let digits = value.filter { $0.isASCII && $0.isNumber }
        self.port = String(digits.prefix(5))
        self.portError = nil
    }

    public func toggleTokenVisibility() {
        self.tokenVisible.toggle()
    }

    /// Parses a URL and populates scheme/host/port (e.g. from a QR scan or LAN result).
    public func setPrefillURL(_ url: URL) {
        let scheme = url.scheme?.lowercased()
        let host = url.host ?? ""
        let parsedPort = url.port.flatMap { $0 > 0 ? String($0) : nil } ?? Self.defaultPort
        self.useHttps = (scheme == "https")
        self.host = host
        self.port = parsedPort
        self.hostError = nil
        self.portError = nil
    }

    // MARK: - Connect

    public func connect() {
        let hostError = validateHost(host)
        let portError = validatePort(port)
        if hostError != nil || portError != nil {
            self.hostError = hostError
            self.portError = portError
            return
        }

        let url = self.assembledURL
        let tokenValue: String? = {
            let trimmed = token.trimmingCharacters(in: .whitespaces)
            return trimmed.isEmpty ? nil : trimmed
        }()

        self.isConnecting = true
        self.lastError = nil

        Task {
            do {
                try await self.connectionManager.connect(url: url, token: tokenValue)

                // Read display name from current state if connected.
                let displayName: String = {
                    if case let .connected(_, name, _, _, _, _, _, _) = self.connectionManager.currentState {
                        return name
                    }
                    return url
                }()

                // Look up any existing profile with this URL to preserve its id.
                let existingId: UUID? = await self.firstProfileId(matching: url)
                let profile = ConnectionProfile(
                    id: existingId ?? UUID(),
                    displayName: displayName,
                    url: url,
                    lastConnectedAt: Date(),
                    hasToken: tokenValue != nil
                )
                try await self.profileRepository.saveProfile(profile, token: tokenValue)
                await self.onboardingRepository.markCompleted()

                self.isConnecting = false
                self.navigateToDashboard = true
            } catch {
                self.isConnecting = false
                self.lastError = (error as? LocalizedError)?.errorDescription
                    ?? "Connect failed: \(String(describing: error))"
            }
        }
    }

    public func consumeNavigation() {
        self.navigateToDashboard = false
    }

    // MARK: - Validation (direct port of Kotlin)

    private func validateHost(_ host: String) -> String? {
        let trimmed = host.trimmingCharacters(in: .whitespaces)
        if trimmed.isEmpty { return "Host is required" }
        if trimmed.contains("://") { return "Remove scheme — use the HTTPS toggle instead" }
        if trimmed.contains("/") { return "Host must not contain '/'" }
        if trimmed.contains(":") { return "Port goes in the Port field, not the Host field" }
        if trimmed.contains(where: { $0.isWhitespace }) { return "Host must not contain whitespace" }
        return nil
    }

    private func validatePort(_ port: String) -> String? {
        let trimmed = port.trimmingCharacters(in: .whitespaces)
        if trimmed.isEmpty { return "Port is required" }
        guard let n = Int(trimmed) else { return "Port must be a number" }
        if n < 1 || n > 65535 { return "Port must be between 1 and 65535" }
        return nil
    }

    // MARK: - Lookup helper

    private func firstProfileId(matching url: String) async -> UUID? {
        for await list in profileRepository.observeProfiles() {
            return list.first(where: { $0.url == url })?.id
        }
        return nil
    }
}
