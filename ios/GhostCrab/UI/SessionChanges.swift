import Foundation

/// Which gateway session a screen has loaded its data for: the iOS counterpart of Android's `sessionChanges()` and
/// `GatewayConnection.Connected.sessionGeneration`.
///
/// The silent reconnect's `.connected(reconnecting: true)` state is not a new session, so a socket blip neither starts
/// loads that are bound to fail nor resets screen state (unsaved edits included): the screen keeps treating the session
/// as connected. The reconnected session arrives as a new `.connected` (next `sessionGeneration`) and is the one that
/// triggers the reload. A failed reconnect still arrives as `.error`.
struct SessionTracker {

    /// `sessionGeneration` of the session last loaded for, if any.
    private var loaded: Int?

    /// Records the session `state` shows (if live and not reconnecting) as loaded, for a screen that loads it itself.
    ///
    /// - Parameter state: The connection state the screen's own load runs against.
    mutating func markLoaded(_ state: GatewayConnection) {
        guard !state.isReconnecting, let generation = state.sessionGeneration else { return }
        loaded = generation
    }

    /// - Parameter state: The latest connection state.
    /// - Returns: `true` when `state` is a live session other than the one last loaded for (it is recorded as loaded);
    ///   `false` for the reconnecting state, a repeat of the same session, and every state that is not `.connected`.
    mutating func isNewSession(_ state: GatewayConnection) -> Bool {
        guard !state.isReconnecting, let generation = state.sessionGeneration else { return false }
        defer { loaded = generation }
        return generation != loaded
    }
}
