package com.openclaw.ghostcrab.domain.repository

/**
 * Liveness probe against the gateway of the **current** session. Uses the live session client, so
 * it honours the same cleartext policy, timeouts and origin-scoped auth as every other call.
 *
 * Additive interface (v1.1) — no frozen v1.0 contract changes.
 */
interface GatewayHealthChecker {

    /**
     * Performs one `GET /health` on the active session.
     *
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayException on any failure. Until the
     *   not-connected leaf exists, a missing session surfaces as `IllegalStateException`.
     */
    suspend fun check()
}
