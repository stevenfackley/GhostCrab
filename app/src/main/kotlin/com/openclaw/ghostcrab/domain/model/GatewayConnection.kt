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
     * Successfully connected: the signed WebSocket `connect` was answered with `hello-ok` (protocol 4, ADR-0007).
     *
     * @param url Base URL of the gateway (e.g. `http://192.168.1.50:18789`).
     * @param displayName The gateway host from `hello-ok.snapshot.presence` (the `self` entry), else
     *   `OpenClaw Gateway`.
     * @param version `hello-ok.server.version`.
     * @param authRequirement The gateway's auth mode from `hello-ok` (`snapshot.authMode`, else `auth.method`).
     * @param isHttps Whether the connection uses TLS.
     * @param capabilities The screens this session may use: `"config"`, `"models"`, `"skills"`, each listed only when
     *   the gateway offers its methods and `hello-ok.auth.scopes` grants the scope they need. Empty on a scope-less
     *   socket.
     * @param hardwareInfo Hardware description; protocol 4 reports none, so always `null` (kept for the v1.0 API).
     * @param tokenOrNull The shared gateway token this session connected with, sent only inside the signed `connect`;
     *   `null` when none was used. Sensitive — never log.
     * @param warnings Additive (v1.1, ADR-0007): problems that did not fail the connect, e.g. a device token that
     *   could not be stored or a regenerated device identity. Empty when there are none.
     * @param reconnecting Additive (v1.1, ADR-0007): `true` while the socket dropped and the manager makes its one
     *   silent reconnect. No session is live meanwhile: RPCs fail with a retryable
     *   [com.openclaw.ghostcrab.domain.exception.GatewayNotConnectedException] ("Reconnecting to <url>…"). The
     *   state then becomes a new [Connected] (higher [sessionGeneration]) or [Error].
     * @param sessionGeneration Additive (v1.1, ADR-0007): increases with every session the manager makes live, so a
     *   reconnected session is a new value even when nothing else changed; screens reload on it.
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
        val reconnecting: Boolean = false,
        val sessionGeneration: Long = 0L,
    ) : GatewayConnection {
        /** Redacts [tokenOrNull] so logging or dumping the connection state cannot leak a bearer. */
        override fun toString(): String =
            "Connected(url=$url, displayName=$displayName, version=$version, " +
                "authRequirement=$authRequirement, isHttps=$isHttps, capabilities=$capabilities, " +
                "hardwareInfo=$hardwareInfo, tokenOrNull=${tokenOrNull?.let { "[REDACTED]" }}, warnings=$warnings, " +
                "reconnecting=$reconnecting, sessionGeneration=$sessionGeneration)"
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
