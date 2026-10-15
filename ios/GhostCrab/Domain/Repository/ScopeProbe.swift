import Foundation

/// Reports the operator scopes the gateway granted the live session (`hello-ok.auth.scopes`, ADR-0007).
///
/// Upstream has no `auth.whoami`: ``ScopeProbeResult/unknownOldGateway`` is no longer produced; it stays for
/// source compatibility.
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
