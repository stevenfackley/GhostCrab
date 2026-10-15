package com.openclaw.ghostcrab.domain.repository

/**
 * Liveness probe against the gateway of the **current** session. Uses the live session's control-plane socket
 * (ADR-0007), so it fails as soon as that socket is gone.
 *
 * Additive (v1.1): new interface, no frozen v1.0 contract changes (ADR-0005).
 */
interface GatewayHealthChecker {

    /**
     * Performs one `health` RPC on the active session's socket.
     *
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayException on any failure, including
     *   [com.openclaw.ghostcrab.domain.exception.GatewayNotConnectedException] when no session is active.
     */
    suspend fun check()
}
