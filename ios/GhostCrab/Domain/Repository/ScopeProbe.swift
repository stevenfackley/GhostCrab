import Foundation

/// Runs `auth.whoami` over WebSocket to learn what scopes the current token carries.
///
/// Treats "unknown" (old gateways without the method) as a distinct state so callers
/// can fall back to the Phase-4 copy-paste install instructions instead of showing a
/// misleading "scope missing" error.
public protocol ScopeProbe: Sendable {
    /// - Returns: A `ScopeProbeResult` describing the outcome. Never throws.
    func probe() async -> ScopeProbeResult
}

/// Result of a scope probe.
///
/// Direct port of the Kotlin `sealed interface ScopeProbeResult`.
public enum ScopeProbeResult: Sendable {

    /// The gateway responded with a concrete scope set.
    case known(scopes: Set<String>)

    /// Gateway did not implement `auth.whoami` — treat feature as "unknown capability".
    case unknownOldGateway

    /// Probe failed (network etc.). Caller decides UX.
    case failed(cause: String)

    /// Convenience: whether a `.known` result contains the given scope. Returns `false` for
    /// `.unknownOldGateway` and `.failed`.
    public func has(_ scope: String) -> Bool {
        if case .known(let scopes) = self { return scopes.contains(scope) }
        return false
    }
}
