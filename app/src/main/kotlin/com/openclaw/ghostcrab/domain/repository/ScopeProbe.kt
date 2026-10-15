package com.openclaw.ghostcrab.domain.repository

/**
 * Reports which operator scopes the current gateway session holds.
 *
 * ADR-0007: the scopes come from `hello-ok.auth.scopes` of the connect handshake (upstream has no
 * `auth.whoami`). [ScopeProbeResult.UnknownOldGateway] is no longer produced; it stays for source compatibility.
 */
interface ScopeProbe {
    /** @return result; never throws. */
    suspend fun probe(): ScopeProbeResult
}

sealed interface ScopeProbeResult {
    data class Known(val scopes: Set<String>) : ScopeProbeResult {
        fun has(scope: String): Boolean = scope in scopes
    }
    /** Gateway did not implement `auth.whoami` — treat feature as "unknown capability". */
    data object UnknownOldGateway : ScopeProbeResult
    /** Probe failed (network etc.). Caller decides UX. */
    data class Failed(val cause: String) : ScopeProbeResult
}
