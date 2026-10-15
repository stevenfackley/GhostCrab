package com.openclaw.ghostcrab.data.impl

import com.openclaw.ghostcrab.domain.repository.ScopeProbe
import com.openclaw.ghostcrab.domain.repository.ScopeProbeResult

/**
 * [ScopeProbe] over `hello-ok.auth.scopes` of the live session (ADR-0007). The gateway has no `auth.whoami`;
 * the scopes the connect handshake granted are the socket's live authority. No network call.
 *
 * @param scopes Returns the live session's granted scopes, or `null` while disconnected.
 */
class ScopeProbeImpl(
    private val scopes: () -> Set<String>?,
) : ScopeProbe {

    /**
     * @return [ScopeProbeResult.Known] with the granted scopes (possibly empty), or [ScopeProbeResult.Failed]
     *   while no session is active. Never [ScopeProbeResult.UnknownOldGateway]. Never throws.
     */
    override suspend fun probe(): ScopeProbeResult =
        scopes()?.let { ScopeProbeResult.Known(it) }
            ?: ScopeProbeResult.Failed(cause = "No active gateway session (GatewayNotConnectedException)")
}
