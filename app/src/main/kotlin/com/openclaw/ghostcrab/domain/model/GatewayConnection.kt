package com.openclaw.ghostcrab.domain.model

import com.openclaw.ghostcrab.domain.exception.GatewayException

/**
 * Represents the current state of the connection to an OpenClaw Gateway.
 *
 * Transitions: [Disconnected] → [Connecting] → [Connected] | [Error]
 * From [Connected] or [Error]: → [Disconnected] via explicit disconnect.
 */
sealed interface GatewayConnection {

    /** No active connection. Initial and terminal state. */
    data object Disconnected : GatewayConnection

    /**
     * Connection handshake in progress.
     *
     * @param url The URL being connected to.
     */
    data class Connecting(val url: String) : GatewayConnection

    /**
     * Successfully connected and authenticated.
     *
     * @param url Base URL of the gateway (e.g. `http://192.168.1.50:18789`).
     * @param displayName Human-readable name from the gateway's `/status` response.
     * @param version Gateway version string.
     * @param authRequirement The auth mode reported by the gateway.
     * @param isHttps Whether the connection uses TLS.
     * @param capabilities Capability keys advertised by the gateway (e.g. `"skill-ai-recommend"`).
     */
    data class Connected(
        val url: String,
        val displayName: String,
        val version: String,
        val authRequirement: AuthRequirement,
        val isHttps: Boolean,
        val capabilities: List<String>,
    ) : GatewayConnection

    /**
     * Connection failed or was lost.
     *
     * @param url The URL that was being connected to.
     * @param cause The exception that caused the failure.
     */
    data class Error(
        val url: String,
        val cause: GatewayException,
    ) : GatewayConnection
}
