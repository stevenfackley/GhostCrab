package com.openclaw.ghostcrab.domain.repository

/**
 * Runs `auth.whoami` over WebSocket to learn what scopes the current token carries.
 *
 * Treats "unknown" (old gateways without the method) as a distinct state so callers
 * can fall back to the Phase-4 copy-paste install instructions instead of showing a
 * misleading "scope missing" error.
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
