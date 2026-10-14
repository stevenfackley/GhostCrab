import Foundation

/// Turns a URL the OS hands to `onOpenURL` into a navigation step.
///
/// Any app or web page can open `ghostcrab://pair`, so the result is only ever
/// a Manual Entry pre-fill: nothing connects until the user reads the host and
/// taps Connect. The token and suggested name go to ``PairingHandoff``; the returned route
/// carries the gateway URL only. Mirrors Android's `pairLinkToHandle` gate.
@MainActor
enum PairingDeepLink {

    /// - Parameters:
    ///   - url: URL from `onOpenURL`.
    ///   - handoff: Receives the pairing token and suggested name, if the link carries them.
    /// - Returns: `.manualEntry(prefillURL:)` for a valid pairing link, or `nil`
    ///   when `url` does not use the `ghostcrab` scheme (not ours to handle).
    /// - Throws: ``PairingLinkError`` for a `ghostcrab:` link that fails validation.
    ///   Nothing is stashed in that case.
    static func route(for url: URL, handoff: PairingHandoff) throws -> Route? {
        guard url.scheme?.lowercased() == PairingLink.scheme else { return nil }
        let request = try PairingLink.decode(url.absoluteString)
        handoff.stash(request)
        return .manualEntry(prefillURL: request.gatewayURL)
    }

    /// Alert text for a rejected link: scheme, host and the reason. Never the
    /// payload, which may contain the token.
    ///
    /// - Parameters:
    ///   - url: The rejected URL.
    ///   - error: Why it was rejected.
    /// - Returns: For example `ghostcrab://pair — base64 decode failed`.
    static func rejectionMessage(for url: URL, error: any Error) -> String {
        let host = url.host.map { String($0.prefix(64)) } ?? "?"
        return "\(PairingLink.scheme)://\(host) \u{2014} \(error.localizedDescription)"
    }
}
