package com.openclaw.ghostcrab.domain.model.config

import kotlinx.serialization.Serializable

/** Authentication mode advertised by the gateway. */
enum class AuthMode { none, bearer }

/**
 * Typed representation of the `gateway.auth` config sub-section.
 *
 * @param mode Whether the gateway requires a bearer token.
 * @param token The bearer token value. Only meaningful when [mode] is [AuthMode.bearer].
 */
@Serializable
data class GatewayAuthSection(
    val mode: AuthMode = AuthMode.none,
    val token: String? = null,
)
