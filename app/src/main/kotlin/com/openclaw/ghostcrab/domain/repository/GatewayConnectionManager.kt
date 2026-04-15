package com.openclaw.ghostcrab.domain.repository

import com.openclaw.ghostcrab.domain.exception.GatewayAuthException
import com.openclaw.ghostcrab.domain.exception.GatewayTimeoutException
import com.openclaw.ghostcrab.domain.exception.GatewayUnreachableException
import com.openclaw.ghostcrab.domain.model.AuthRequirement
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import kotlinx.coroutines.flow.StateFlow

/**
 * Manages the lifecycle of a single active gateway connection.
 *
 * All state is exposed as a hot [StateFlow]. Only one connection is active at a time.
 *
 * **Contract frozen at v1.0.** Do not change signatures without an ADR.
 */
interface GatewayConnectionManager {

    /**
     * Current connection state. Starts at [GatewayConnection.Disconnected].
     * Updates are delivered on the main thread.
     */
    val connectionState: StateFlow<GatewayConnection>

    /**
     * Determines the authentication requirement for a gateway without storing any state.
     *
     * Probes `GET /health` (unauthenticated) then `GET /status` (unauthenticated).
     * - `/health` fails → [GatewayUnreachableException]
     * - `/status` returns 401 or 403 → [AuthRequirement.Token]
     * - `/status` returns 2xx → [AuthRequirement.None]
     *
     * @param url Base URL of the gateway (scheme + host + port, no trailing slash).
     * @return The [AuthRequirement] inferred from the probe.
     * @throws GatewayUnreachableException if the host is not reachable.
     * @throws GatewayTimeoutException if either probe times out.
     */
    suspend fun probeAuth(url: String): AuthRequirement

    /**
     * Connects to a gateway.
     *
     * 1. Emits [GatewayConnection.Connecting].
     * 2. Calls [probeAuth] to determine auth mode.
     * 3. Instantiates an authenticated Ktor client.
     * 4. Fetches `/status` to read version and capabilities.
     * 5. Emits [GatewayConnection.Connected] on success.
     * 6. On any failure: emits [GatewayConnection.Error] **and** rethrows the exception.
     *
     * Calling [connect] while already [GatewayConnection.Connected] disconnects first.
     *
     * @param url Base URL of the gateway.
     * @param token Bearer token for authentication. Pass `null` for unauthenticated gateways.
     * @throws GatewayUnreachableException if the host is not reachable.
     * @throws GatewayAuthException if authentication fails.
     * @throws GatewayTimeoutException if the connection times out.
     */
    suspend fun connect(url: String, token: String? = null)

    /**
     * Disconnects from the current gateway.
     *
     * Cancels the held Ktor client, emits [GatewayConnection.Disconnected].
     * No-op if already disconnected.
     */
    suspend fun disconnect()
}
