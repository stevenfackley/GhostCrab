import Foundation

/// What a pairing link hands to the manual-entry form.
///
/// `description`, `debugDescription` and the reflection mirror redact ``token``.
public struct PairedGateway: Sendable, Equatable {
    /// Bearer token decoded from the pairing payload, or `nil`. Sensitive: never log it.
    public let token: String?
    /// Profile name suggested by the link (`name` on the wire), as decoded, or `nil`.
    public let displayName: String?

    /// - Parameters:
    ///   - token: Bearer token, or `nil`.
    ///   - displayName: Suggested profile name, or `nil`.
    public init(token: String?, displayName: String?) {
        self.token = token
        self.displayName = displayName
    }
}

extension PairedGateway: CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    /// Redacted form: the token is replaced by a placeholder.
    public var description: String {
        "PairedGateway(token: \(token == nil ? "nil" : "<redacted>"), displayName: \(displayName ?? "nil"))"
    }

    /// Same as ``description``.
    public var debugDescription: String { description }

    /// Mirror without the token, so `dump` stays redacted.
    public var customMirror: Mirror {
        Mirror(
            self,
            children: [
                "token": token == nil ? "nil" : "<redacted>",
                "displayName": displayName ?? "nil",
            ],
            displayStyle: .struct
        )
    }
}

/// One-shot, in-memory hand-off of a pairing bearer token and suggested
/// profile name from the QR scanner or a `ghostcrab://pair` deep link to the
/// manual-entry form.
///
/// Why not `Route.manualEntry(prefillURL:token:)`: `Route` is `Hashable` and
/// lives in `NavigationPath`, which SwiftUI may encode for state restoration,
/// so a token there could be written to disk. Here it lives only in memory,
/// is cleared by the first ``takePairing(for:)`` / ``take(for:)`` (matching or
/// not) and expires after ``defaultTTL`` (or the injected `ttl`), so an
/// unconsumed token never fills a later, unrelated visit. It reaches
/// persistent storage only through the normal connect,
/// `ConnectionProfileRepository.saveProfile`, Keychain path.
/// Mirrors Android's `PairingHandoff`.
///
/// `description`, `debugDescription` and the reflection mirror are redacted.
///
/// Owned by ``AppContainer`` (one per app process).
@MainActor
public final class PairingHandoff {

    /// How long a stash stays takeable when no `ttl` is injected.
    public nonisolated static let defaultTTL: Duration = .seconds(120)

    private struct Pending {
        let url: URL
        let paired: PairedGateway
        let stashedAt: ContinuousClock.Instant
    }

    private var pending: Pending?
    private let ttl: Duration
    private let now: @MainActor () -> ContinuousClock.Instant

    /// - Parameters:
    ///   - ttl: How long a stash stays takeable. Defaults to ``defaultTTL``.
    ///   - now: Monotonic clock for expiry; injectable for tests.
    public init(
        ttl: Duration = PairingHandoff.defaultTTL,
        now: @escaping @MainActor () -> ContinuousClock.Instant = { ContinuousClock().now }
    ) {
        self.ttl = ttl
        self.now = now
    }

    /// Stores what a decoded pairing link carries for its gateway URL.
    /// Same rules as ``stash(token:displayName:for:)``.
    ///
    /// - Parameter request: The decoded pairing link.
    public func stash(_ request: PairingRequest) {
        stash(token: request.token, displayName: request.displayName, for: request.gatewayURL)
    }

    /// Stores `token` and `displayName` for the gateway at `url`, replacing any
    /// earlier stash. With neither a token nor a name there is nothing to hand
    /// over, so the earlier stash is cleared instead.
    ///
    /// - Parameters:
    ///   - token: Bearer token decoded from the pairing payload. Sensitive: never log it.
    ///   - displayName: Profile name suggested by the link, as decoded.
    ///   - url: Gateway base URL the pairing belongs to.
    public func stash(token: String?, displayName: String? = nil, for url: URL) {
        guard token != nil || displayName != nil else {
            pending = nil
            return
        }
        pending = Pending(
            url: url,
            paired: PairedGateway(token: token, displayName: displayName),
            stashedAt: now()
        )
    }

    /// Returns the stash if it is younger than the TTL and `url` has the same
    /// scheme, host (case-insensitive) and port as the stashed URL.
    ///
    /// Always clears the stash, matching or not, so it is read at most once
    /// and never reaches a different gateway.
    ///
    /// - Parameter url: The URL the manual-entry form was pre-filled with.
    /// - Returns: The token and suggested name, or `nil` if nothing (unexpired)
    ///   was stashed for that origin.
    public func takePairing(for url: URL) -> PairedGateway? {
        guard let taken = pending else { return nil }
        pending = nil
        guard taken.stashedAt.duration(to: now()) < ttl, Self.sameOrigin(taken.url, url) else {
            return nil
        }
        return taken.paired
    }

    /// Token-only form of ``takePairing(for:)``; clears the stash the same way.
    ///
    /// - Parameter url: The URL the manual-entry form was pre-filled with.
    /// - Returns: The token, or `nil` if none (unexpired) was stashed for that origin.
    public func take(for url: URL) -> String? {
        takePairing(for: url)?.token
    }

    /// Scheme, host (case-insensitive) and port equality. Ports must match
    /// exactly: both sides come from ``PairingLink/gatewayURL(from:)``, which
    /// always makes the port explicit.
    static func sameOrigin(_ a: URL, _ b: URL) -> Bool {
        a.scheme?.lowercased() == b.scheme?.lowercased()
            && a.host?.lowercased() == b.host?.lowercased()
            && a.port == b.port
    }
}

extension PairingHandoff: CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    /// Constant, redacted text: never includes the token or the gateway URL.
    public nonisolated var description: String { "PairingHandoff(<redacted>)" }

    /// Same as ``description``.
    public nonisolated var debugDescription: String { description }

    /// Mirror with no children, so `dump` never walks into the stash.
    public nonisolated var customMirror: Mirror {
        Mirror(self, children: [:], displayStyle: .class)
    }
}
