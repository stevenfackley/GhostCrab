import Foundation

/// Turns a URL the OS hands to `onOpenURL` into a navigation step.
///
/// Any app or web page can open `ghostcrab://pair`, so the result is only ever
/// a Manual Entry pre-fill: nothing connects until the user reads the host and
/// taps Connect. The token and suggested name go to ``PairingHandoff``; only the
/// gateway URL enters the navigation stack. Mirrors Android's `pairLinkToHandle` gate.
@MainActor
enum PairingDeepLink {

    /// Handles a URL from `onOpenURL`: stashes the link's token and name in
    /// `handoff`, then shows Manual Entry pre-filled with its gateway URL.
    ///
    /// The OS can deliver one link twice. When Manual Entry for that gateway
    /// is already on top, the repeat does nothing: the screen took (or will
    /// take, on appear) the first stash, so the token is handed off once and
    /// no second Manual Entry is stacked.
    ///
    /// - Parameters:
    ///   - url: URL from `onOpenURL`.
    ///   - handoff: Receives the pairing token and suggested name, if the link carries them.
    ///   - navigator: The stack Manual Entry is shown on.
    /// - Returns: `false` when `url` does not use the `ghostcrab` scheme (not
    ///   ours to handle; nothing changes), else `true`.
    /// - Throws: ``PairingLinkError`` for a `ghostcrab:` link that fails validation.
    ///   Nothing is stashed and the stack is unchanged in that case.
    @discardableResult
    static func open(_ url: URL, handoff: PairingHandoff, navigator: Navigator) throws -> Bool {
        guard url.scheme?.lowercased() == PairingLink.scheme else { return false }
        let request = try PairingLink.decode(url.absoluteString)
        guard !navigator.isShowingManualEntry(prefillURL: request.gatewayURL) else { return true }
        handoff.stash(request)
        navigator.showManualEntry(prefillURL: request.gatewayURL)
        return true
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
