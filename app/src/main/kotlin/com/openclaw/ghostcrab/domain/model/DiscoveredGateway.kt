package com.openclaw.ghostcrab.domain.model

/**
 * A gateway discovered via mDNS (NsdManager).
 *
 * Service type: `_openclaw-gw._tcp.` Default port: 18789.
 * Deduplication key: [instanceName].
 *
 * @param instanceName mDNS service instance name (unique per gateway on the network).
 * @param hostAddress Resolved IPv4/IPv6 address.
 * @param port TCP port the gateway is listening on.
 * @param displayName Human-readable name from TXT record or instance name fallback.
 * @param version Gateway version from TXT record, or `null` if not advertised.
 */
data class DiscoveredGateway(
    val instanceName: String,
    val hostAddress: String,
    val port: Int,
    val displayName: String,
    val version: String?,
) {
    /** Constructed base URL for use with [com.openclaw.ghostcrab.domain.repository.GatewayConnectionManager]. */
    val url: String get() = "http://$hostAddress:$port"
}
