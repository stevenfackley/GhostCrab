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
    /// Mirrors Android's `pairedName`. Dropped when the scheme, host or port
    /// is edited by hand: the name belongs to the paired gateway only.
    public private(set) var pairedName: String?

    /// `true` while ``token`` is the one a pairing link brought in, not one the
    /// user typed. A hand edit of the scheme, host or port drops it.
    @ObservationIgnored private var tokenFromPairing = false

    /// Whether the navigation route's URL was applied (``applyRoutePrefill(_:)``
    /// or ``pairingRedelivered(_:routeURL:)``). Applied once, so a later appear
    /// never wipes what the user typed or a token just taken from the hand-off.
    @ObservationIgnored private var routePrefillApplied = false

    /// Text of the "Pairing link" field: a `ghostcrab://pair` link or a gateway
    /// URL. Only ``applyPastedLink()`` reads it, never each keystroke. Can hold a
    /// token: cleared once applied.
    public var pastedLink: String = ""

    /// Why the last ``applyPastedLink()`` was rejected, or `nil`. Never echoes
    /// the link (a pairing link contains the token).
    public private(set) var pastedLinkError: String?

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
        let bareHost = Self.urlHost(host)
        let hostPart = bareHost.contains(":") ? "[\(bareHost)]" : bareHost
        return "\(scheme)://\(hostPart):\(port.trimmingCharacters(in: .whitespaces))"
    }

    /// `true` when HTTPS is off and iOS ATS will refuse plain HTTP to ``host``
    /// (a qualified hostname that is neither an IP literal nor `.local`).
    /// The screen shows a warning; connecting would fail before the request leaves the device.
    public var cleartextBlockedByATS: Bool {
        !useHttps && ATSCleartextPolicy.blocksCleartext(host: host)
    }

    // MARK: - Field setters

    /// Host typed by hand. A change drops a pairing link's name and token
    /// (see ``addressEditedByHand()``).
    public func setHost(_ value: String) {
        if value != host { addressEditedByHand() }
        self.host = value
        self.hostError = nil
    }

    /// Port typed by hand: digits only, at most five. A change drops a pairing
    /// link's name and token (see ``addressEditedByHand()``).
    public func setPort(_ value: String) {
        let digits = value.filter { $0.isASCII && $0.isNumber }
        let next = String(digits.prefix(5))
        if next != port { addressEditedByHand() }
        self.port = next
        self.portError = nil
    }

    /// HTTPS toggled by hand. A change drops a pairing link's name and token
    /// (see ``addressEditedByHand()``).
    public func setUseHttps(_ value: Bool) {
        if value != useHttps { addressEditedByHand() }
        self.useHttps = value
    }

    /// Token typed by hand. It is the user's own from then on, so a later
    /// address edit keeps it.
    public func setToken(_ value: String) {
        guard value != token else { return }
        self.token = value
        self.tokenFromPairing = false
    }

    public func toggleTokenVisibility() {
        self.tokenVisible.toggle()
    }

    /// The form now names a different gateway than the pairing link did, so
    /// the link's suggested name no longer applies, and neither does its token
    /// unless the user typed over it. Without this, gateway B would be saved
    /// under A's name, and could be sent A's token.
    private func addressEditedByHand() {
        pairedName = nil
        if tokenFromPairing {
            token = ""
            tokenFromPairing = false
        }
    }

    // MARK: - Route prefill

    /// Applies the navigation route's URL on first appearance. Later calls do
    /// nothing, including a first appear that comes after
    /// ``pairingRedelivered(_:routeURL:)`` already filled the form, so it never
    /// wipes what the user typed or a token just taken from the hand-off.
    ///
    /// - Parameter url: The route's prefill URL, or `nil` for an empty form.
    public func applyRoutePrefill(_ url: URL?) {
        guard !routePrefillApplied else { return }
        routePrefillApplied = true
        if let url { setPrefillURL(url) }
    }

    /// A pairing link for the gateway this screen was opened for arrived again
    /// (perhaps re-issued with a new token): the newer link wins and its stash
    /// fills the form. A link for any other gateway is ignored here.
    ///
    /// - Parameters:
    ///   - url: Gateway URL of the re-delivered link. Never a token.
    ///   - routeURL: The route's prefill URL.
    public func pairingRedelivered(_ url: URL, routeURL: URL?) {
        guard let routeURL, url == routeURL else { return }
        routePrefillApplied = true
        setPrefillURL(routeURL)
    }

    /// Parses a URL and populates scheme/host/port (from a QR scan, a deep
    /// link, a pasted link or a LAN result).
    ///
    /// The form's ``token`` and ``pairedName`` are dropped first, on every
    /// call: credentials typed or pasted for one gateway never ride along to
    /// the gateway this URL names. Then, if a pairing link was stashed for this
    /// URL's origin (QR, deep link or paste), its token fills ``token`` and its
    /// suggested name ``pairedName``. The hand-off is one-shot. The screen
    /// applies its route's URL once (and again only when a pairing link for
    /// the same gateway is re-delivered), so this never wipes what the user
    /// typed afterwards. Never connects: the user still reviews the form and
    /// taps Connect.
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
        // Never carry a token (or a suggested name) to a different host.
        self.token = ""
        self.tokenFromPairing = false
        self.pairedName = nil
        if let paired = pairingHandoff.takePairing(for: url) {
            if let pairedToken = paired.token {
                self.token = pairedToken
                self.tokenFromPairing = true
            }
            let name = paired.displayName?.trimmingCharacters(in: .whitespacesAndNewlines)
            self.pairedName = (name?.isEmpty ?? true) ? nil : name
        }
    }

    /// Fills the form from ``pastedLink``. This is how Mac Catalyst, which has
    /// no QR scanner, takes a pairing link, and how any device takes a link
    /// copied from a message.
    ///
    /// - A `ghostcrab:` link is decoded by ``PairingLink`` and goes through
    ///   ``PairingHandoff`` like a scanned QR code: its URL fills scheme, host
    ///   and port, its token fills ``token`` and its name becomes ``pairedName``.
    /// - Anything else must pass ``PairingLink/gatewayURL(from:)`` (`http` or
    ///   `https`, a host, no userinfo); it fills scheme, host and port only.
    ///
    /// Either way the token and suggested name already in the form are
    /// dropped (``setPrefillURL(_:)``), so pasting a link for gateway B after
    /// one for gateway A never sends A's token to B.
    ///
    /// ``pastedLink`` is cleared whether or not it was accepted: it can hold a
    /// token or userinfo credentials. On failure ``pastedLinkError`` is set and
    /// the rest of the form is left as it was. Never connects: the user still
    /// reviews the form and taps Connect.
    public func applyPastedLink() {
        guard pastedLink.utf8.count <= PairingLink.maxLength else {
            rejectPaste("Link is too large (over \(PairingLink.maxLength) characters)")
            return
        }
        let trimmed = pastedLink.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            rejectPaste("Paste a gateway URL or a ghostcrab://pair link first")
            return
        }

        if PairingLink.isPairingLink(trimmed) {
            let request: PairingRequest
            do {
                request = try PairingLink.decode(trimmed)
            } catch {
                rejectPaste("Pairing link is malformed: \(error.localizedDescription)")
                return
            }
            pairingHandoff.stash(request)
            setPrefillURL(request.gatewayURL)
        } else if let url = PairingLink.gatewayURL(from: trimmed) {
            setPrefillURL(url)
        } else {
            rejectPaste("Not an http(s) gateway URL or a ghostcrab://pair link")
            return
        }
        pastedLink = ""
        pastedLinkError = nil
    }

    /// Shows why a paste was refused and clears the field, which may hold a
    /// token or credentials. The error never echoes the pasted text.
    private func rejectPaste(_ message: String) {
        pastedLink = ""
        pastedLinkError = message
    }

    // MARK: - Connect

    /// Validates the form, connects, saves the profile + token, marks onboarding
    /// completed, then flips ``navigateToDashboard``. Ignored while a connect is
    /// already in flight, so a double tap connects once.
    public func connect() {
        guard connectTask == nil else { return }
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
            // A cancelled task leaves `connectTask` alone: `cancelConnect()` already cleared it.
            defer { if !Task.isCancelled { self.connectTask = nil } }
            var gatewayAccepted = false
            do {
                try await self.connectionManager.connect(url: url, token: tokenValue)
                gatewayAccepted = true
                // Left the screen while connecting: save nothing, navigate nowhere.
                try Task.checkCancellation()

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
                try Task.checkCancellation()

                self.isConnecting = false
                self.navigateToDashboard = true
            } catch {
                guard !Task.isCancelled else {
                    // Left the screen after the gateway accepted the connect:
                    // no dashboard will show this session, so don't leave it
                    // live behind the picker. (A cancel before that point
                    // already left the manager disconnected.)
                    if gatewayAccepted {
                        await self.connectionManager.disconnect()
                    }
                    return
                }
                self.isConnecting = false
                self.lastError = (error as? LocalizedError)?.errorDescription
                    ?? "Connect failed: \(String(describing: error))"
            }
        }
    }

    /// Abandons an in-flight connect. Call from the screen's `.onDisappear`
    /// (Back while connecting), like Android cancelling the attempt with the
    /// ViewModel's scope: no error is shown, no navigation follows, and the
    /// profile is not saved unless its save had already started. The manager
    /// ends disconnected either way: a handshake still running stops there,
    /// and a session the gateway had already accepted is disconnected.
    /// No-op when nothing is in flight.
    public func cancelConnect() {
        guard let task = connectTask else { return }
        task.cancel()
        connectTask = nil
        isConnecting = false
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
