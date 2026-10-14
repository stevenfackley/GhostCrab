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
    private let pairingHandoff: PairingHandoff

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

    /// Profile name suggested by a pairing link (trimmed, non-blank), or `nil`.
    /// Saved as the profile's display name instead of the gateway's own name.
    /// Mirrors Android's `pairedName`.
    public private(set) var pairedName: String?

    /// In-flight connect, if any. Read-only outside the VM so tests can await it.
    @ObservationIgnored private(set) var connectTask: Task<Void, Never>?

    // MARK: - Init

    /// - Parameters:
    ///   - connectionManager: Drives the connect flow.
    ///   - profileRepository: Persists the profile + token after a successful connect.
    ///   - onboardingRepository: Marked completed after a successful connect.
    ///   - pairingHandoff: One-shot source of a token from QR / deep-link pairing.
    public init(
        connectionManager: any GatewayConnectionManager,
        profileRepository: any ConnectionProfileRepository,
        onboardingRepository: any OnboardingRepository,
        pairingHandoff: PairingHandoff
    ) {
        self.connectionManager = connectionManager
        self.profileRepository = profileRepository
        self.onboardingRepository = onboardingRepository
        self.pairingHandoff = pairingHandoff
    }

    // MARK: - Derived

    public var scheme: String { useHttps ? "https" : "http" }

    /// `scheme://host:port` — exact URL that will be hit, shown beneath the inputs.
    /// An IPv6 literal is bracketed and loses its zone id (`fe80::1%en0` →
    /// `http://[fe80::1]:18789`), like Android's `GatewayUrl`.
    public var assembledURL: String {
        let urlHost = Self.urlHost(host)
        let hostPart = urlHost.contains(":") ? "[\(urlHost)]" : urlHost
        return "\(scheme)://\(hostPart):\(port.trimmingCharacters(in: .whitespaces))"
    }

    /// `true` when HTTPS is off and iOS ATS will refuse plain HTTP to ``host``
    /// (a qualified hostname that is neither an IP literal nor `.local`).
    /// The screen shows a warning; connecting would fail before the request leaves the device.
    public var cleartextBlockedByATS: Bool {
        !useHttps && ATSCleartextPolicy.blocksCleartext(host: host)
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
    ///
    /// If a pairing link was stashed for this URL's origin (QR or deep link),
    /// its token is moved into ``token`` and its suggested name into
    /// ``pairedName``. The hand-off is one-shot, so later calls (the screen
    /// calls this on every appear) leave both alone. Never connects: the user
    /// still reviews the form and taps Connect.
    ///
    /// - Parameter url: Gateway base URL.
    public func setPrefillURL(_ url: URL) {
        let scheme = url.scheme?.lowercased()
        let host = url.host ?? ""
        let parsedPort = url.port.flatMap { $0 > 0 ? String($0) : nil } ?? Self.defaultPort
        self.useHttps = (scheme == "https")
        self.host = host
        self.port = parsedPort
        self.hostError = nil
        self.portError = nil
        if let paired = pairingHandoff.takePairing(for: url) {
            if let pairedToken = paired.token {
                self.token = pairedToken
            }
            let name = paired.displayName?.trimmingCharacters(in: .whitespacesAndNewlines)
            self.pairedName = (name?.isEmpty ?? true) ? nil : name
        }
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

        connectTask = Task {
            defer { self.connectTask = nil }
            do {
                try await self.connectionManager.connect(url: url, token: tokenValue)

                // A pairing link's suggested name wins; otherwise the name the
                // gateway reports once connected; otherwise the URL.
                let displayName: String
                if let pairedName = self.pairedName {
                    displayName = pairedName
                } else if case let .connected(_, name, _, _, _, _, _, _) = self.connectionManager.currentState {
                    displayName = name
                } else {
                    displayName = url
                }

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
        // IPv6 literal, bare or bracketed (LAN discovery, a QR code, or typed).
        let bare = Self.unbracketed(trimmed)
        if Self.isIPv6Literal(bare) { return nil }
        // Zone ids (fe80::1%en0) are IPv6-only; assembledURL drops them.
        if trimmed.contains("%") {
            return Self.isIPv6WithZone(bare) ? nil : "Host must not contain '%' (IPv6 zone ids only)"
        }
        if trimmed.contains(":") { return "Port goes in the Port field, not the Host field" }
        if trimmed.contains("[") || trimmed.contains("]") { return "Brackets are only allowed around an IPv6 address" }
        if trimmed.contains(where: { $0.isWhitespace }) { return "Host must not contain whitespace" }
        // '@' would smuggle userinfo into the assembled URL; '?' and '#' would truncate the host.
        if trimmed.contains(where: { "@?#\\".contains($0) }) { return "Host must not contain '@', '?', '#' or '\\'" }
        return nil
    }

    // MARK: - IPv6 host helpers (mirror Android's validateHost / GatewayUrl)

    /// `host` as it goes into the URL: trimmed, surrounding brackets removed,
    /// and an IPv6 zone id (`%en0`, not valid in a URL authority) dropped.
    private static func urlHost(_ host: String) -> String {
        let bare = unbracketed(host.trimmingCharacters(in: .whitespaces))
        if let percent = bare.firstIndex(of: "%"), isIPv6Literal(String(bare[..<percent])) {
            return String(bare[..<percent])
        }
        return bare
    }

    /// `[…]` → `…`; anything else unchanged.
    private static func unbracketed(_ host: String) -> String {
        guard host.count >= 2, host.hasPrefix("["), host.hasSuffix("]") else { return host }
        return String(host.dropFirst().dropLast())
    }

    /// Hex digits, dots and at least two colons, no brackets.
    private static func isIPv6Literal(_ host: String) -> Bool {
        host.filter { $0 == ":" }.count >= 2
            && host.allSatisfy { $0 == ":" || $0 == "." || ($0.isASCII && $0.isHexDigit) }
    }

    /// An IPv6 literal followed by `%` and a non-empty zone of letters, digits, `-`, `_` or `.`.
    private static func isIPv6WithZone(_ host: String) -> Bool {
        guard let percent = host.firstIndex(of: "%") else { return false }
        let zone = host[host.index(after: percent)...]
        return isIPv6Literal(String(host[..<percent]))
            && !zone.isEmpty
            && zone.allSatisfy { ($0.isASCII && ($0.isLetter || $0.isNumber)) || "-_.".contains($0) }
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
