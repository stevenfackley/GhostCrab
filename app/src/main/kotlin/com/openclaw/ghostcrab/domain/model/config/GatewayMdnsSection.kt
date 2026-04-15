package com.openclaw.ghostcrab.domain.model.config

import kotlinx.serialization.Serializable

/**
 * Typed representation of the `gateway.mdns` config sub-section.
 *
 * @param enabled Whether the gateway advertises itself via mDNS.
 * @param serviceName mDNS service name used for discovery.
 */
@Serializable
data class GatewayMdnsSection(
    val enabled: Boolean = true,
    val serviceName: String = "openclaw-gateway",
)
