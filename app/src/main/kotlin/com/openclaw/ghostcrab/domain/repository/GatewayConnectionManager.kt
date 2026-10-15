package com.openclaw.ghostcrab.domain.repository

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
     * Probes `GET /health`, then a credential-less signed WebSocket `connect` (ADR-0007); the socket is closed
     * before returning.
     * - `/health` fails → [GatewayUnreachableException]
     * - `AUTH_TOKEN_MISSING` / `AUTH_PASSWORD_MISSING` / `AUTH_REQUIRED` → [AuthRequirement.Token]
     * - `hello-ok` → its auth mode; `PAIRING_REQUIRED` → [AuthRequirement.None] (only the device approval is missing)
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
     * 2. Checks `GET /health`.
     * 3. Opens the control-plane WebSocket and sends a signed `connect` (ADR-0007).
     * 4. Reads version, auth mode and capabilities from `hello-ok`.
     * 5. Emits [GatewayConnection.Connected] on success.
     * 6. On any failure: emits [GatewayConnection.Error] **and** rethrows the exception
     *    ([com.openclaw.ghostcrab.domain.exception.GatewayPairingRequiredException] while the device awaits approval).
     *
     * Calling [connect] while already [GatewayConnection.Connected] disconnects first.
     *
     * @param url Base URL of the gateway.
     * @param token Shared gateway token, sent in the `connect` frame (never over HTTP). Pass `null` to use this
     *   gateway's stored device token, or no credential.
     * @throws GatewayUnreachableException if the host is not reachable.
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayConnectAuthException if the gateway rejects the
     *   credential or the device proof.
     * @throws GatewayTimeoutException if the connection times out.
     */
    suspend fun connect(url: String, token: String? = null)

    /**
     * Disconnects from the current gateway.
     *
     * Closes the session's WebSocket, emits [GatewayConnection.Disconnected].
     * No-op if already disconnected.
     */
    suspend fun disconnect()
}
