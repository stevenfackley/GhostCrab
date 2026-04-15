package com.openclaw.ghostcrab.domain.model.config

import kotlinx.serialization.Serializable

/**
 * Typed representation of the `gateway.http` config sub-section.
 *
 * @param host Bind address for the gateway HTTP server.
 * @param port TCP port the gateway listens on. Valid range: 1–65535.
 */
@Serializable
data class GatewayHttpSection(
    val host: String = "0.0.0.0",
    val port: Int = 18789,
)
