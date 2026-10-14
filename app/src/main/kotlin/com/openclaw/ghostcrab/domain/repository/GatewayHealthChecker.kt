package com.openclaw.ghostcrab.domain.repository

/**
 * Liveness probe against the gateway of the **current** session. Uses the live session client, so
 * it honours the same cleartext policy, timeouts and origin-scoped auth as every other call.
 *
 * Additive (v1.1): new interface, no frozen v1.0 contract changes (ADR-0005).
 */
interface GatewayHealthChecker {

    /**
     * Performs one `GET /health` on the active session.
     *
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayException on any failure, including
     *   [com.openclaw.ghostcrab.domain.exception.GatewayNotConnectedException] when no session is active.
     */
    suspend fun check()
}
