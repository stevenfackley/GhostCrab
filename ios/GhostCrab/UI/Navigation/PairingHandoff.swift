import Foundation

/// One-shot, in-memory hand-off of a pairing bearer token from the QR scanner
/// or a `ghostcrab://pair` deep link to the manual-entry form.
///
/// Why not `Route.manualEntry(prefillURL:token:)`: `Route` is `Hashable` and
/// lives in `NavigationPath`, which SwiftUI may encode for state restoration,
/// so a token there could be written to disk. Here it lives only in memory,
/// is cleared by the first ``take(for:)`` (matching or not) and expires after
/// ``defaultTTL`` (or the injected `ttl`), so an unconsumed token never fills a
/// later, unrelated visit. It reaches persistent storage only through the
/// normal connect, `ConnectionProfileRepository.saveProfile`, Keychain path.
/// Mirrors Android's `PairingHandoff`.
///
/// `description`, `debugDescription` and the reflection mirror are redacted.
///
/// Owned by ``AppContainer`` (one per app process).
@MainActor
public final class PairingHandoff {

    /// How long a stashed token stays takeable when no `ttl` is injected.
    public nonisolated static let defaultTTL: Duration = .seconds(120)

    private struct Pending {
        let url: URL
        let token: String
        let stashedAt: ContinuousClock.Instant
    }

    private var pending: Pending?
    private let ttl: Duration
    private let now: @MainActor () -> ContinuousClock.Instant

    /// - Parameters:
    ///   - ttl: How long a stashed token stays takeable. Defaults to ``defaultTTL``.
    ///   - now: Monotonic clock for expiry; injectable for tests.
    public init(
        ttl: Duration = PairingHandoff.defaultTTL,
        now: @escaping @MainActor () -> ContinuousClock.Instant = { ContinuousClock().now }
    ) {
        self.ttl = ttl
        self.now = now
    }

    /// Stores `token` for the gateway at `url`, replacing any earlier stash.
    ///
    /// - Parameters:
    ///   - token: Bearer token decoded from the pairing payload. Sensitive: never log it.
    ///   - url: Gateway base URL the token belongs to.
    public func stash(token: String, for url: URL) {
        pending = Pending(url: url, token: token, stashedAt: now())
    }

    /// Returns the stashed token if it is younger than the TTL and `url` has the
    /// same scheme, host (case-insensitive) and port as the stashed URL.
    ///
    /// Always clears the stash, matching or not, so a token is read at most once
    /// and never reaches a different gateway.
    ///
    /// - Parameter url: The URL the manual-entry form was pre-filled with.
    /// - Returns: The token, or `nil` if nothing (unexpired) was stashed for that origin.
    public func take(for url: URL) -> String? {
        guard let taken = pending else { return nil }
        pending = nil
        guard taken.stashedAt.duration(to: now()) < ttl, Self.sameOrigin(taken.url, url) else {
            return nil
        }
        return taken.token
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
