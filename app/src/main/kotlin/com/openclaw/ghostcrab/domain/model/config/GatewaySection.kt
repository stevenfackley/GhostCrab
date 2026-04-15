package com.openclaw.ghostcrab.domain.model.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.Serializable

/** Lenient [Json] instance used for round-tripping config sections. */
private val configJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Typed representation of the top-level `gateway` config section.
 *
 * @param http HTTP server bind configuration.
 * @param auth Authentication mode and token.
 * @param mdns mDNS advertisement configuration.
 */
@Serializable
data class GatewaySection(
    val http: GatewayHttpSection = GatewayHttpSection(),
    val auth: GatewayAuthSection = GatewayAuthSection(),
    val mdns: GatewayMdnsSection = GatewayMdnsSection(),
)

/**
 * Decodes this [JsonElement] into a [GatewaySection].
 *
 * @return Parsed [GatewaySection], or a default instance if decoding fails.
 */
fun JsonElement.toGatewaySection(): GatewaySection =
    configJson.decodeFromJsonElement(this)

/**
 * Encodes this [GatewaySection] to a [JsonElement] suitable for use as a config patch body.
 *
 * @return Serialized [JsonElement].
 */
fun GatewaySection.toJsonElement(): JsonElement =
    configJson.encodeToJsonElement(this)
