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
     * @param capabilities Capability keys advertised by the gateway (e.g. `"ai.recommend"`).
     * @param hardwareInfo Optional hardware description reported by the gateway's `/status` (e.g. RAM, GPU). `null` if not reported.
     * @param tokenOrNull Bearer token for the active session, or null if auth is not required. Sensitive — never log.
     * @param warnings Additive (v1.1, ADR-0007): problems that did not fail the connect, e.g. a device token that
     *   could not be stored or a regenerated device identity. Empty when there are none.
     */
    data class Connected(
        val url: String,
        val displayName: String,
        val version: String,
        val authRequirement: AuthRequirement,
        val isHttps: Boolean,
        val capabilities: List<String>,
        val hardwareInfo: String? = null,
        val tokenOrNull: String? = null,
        val warnings: List<ConnectionWarning> = emptyList(),
    ) : GatewayConnection {
        /** Redacts [tokenOrNull] so logging or dumping the connection state cannot leak a bearer. */
        override fun toString(): String =
            "Connected(url=$url, displayName=$displayName, version=$version, " +
                "authRequirement=$authRequirement, isHttps=$isHttps, capabilities=$capabilities, " +
                "hardwareInfo=$hardwareInfo, tokenOrNull=${tokenOrNull?.let { "[REDACTED]" }}, warnings=$warnings)"
    }

    /**
     * Connection failed or was lost.
     *
     * @param url The URL that was being connected to.
     * @param cause The exception that caused the failure.
     * @param warnings Additive (v1.1, ADR-0007): context for [cause], e.g. a regenerated device identity that
     *   explains a new pairing request. Empty when there is none.
     */
    data class Error(
        val url: String,
        val cause: GatewayException,
        val warnings: List<ConnectionWarning> = emptyList(),
    ) : GatewayConnection
}
