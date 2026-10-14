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
 * @return Parsed [GatewaySection].
 * @throws IllegalArgumentException (incl. `SerializationException`) when the JSON does not fit the
 *   model, e.g. an unknown `auth.mode`. UI code should use [toGatewaySectionOrNull].
 */
fun JsonElement.toGatewaySection(): GatewaySection =
    configJson.decodeFromJsonElement(this)

/**
 * Decodes this [JsonElement] into a [GatewaySection], or returns `null` when the JSON cannot be
 * represented by the typed model. Callers must then fall back to raw JSON editing — never to
 * defaults, which would misrepresent the gateway's real settings.
 *
 * @return Parsed [GatewaySection], or `null` when decoding fails (`SerializationException` is an
 *   [IllegalArgumentException]).
 */
fun JsonElement.toGatewaySectionOrNull(): GatewaySection? =
    try {
        configJson.decodeFromJsonElement<GatewaySection>(this)
    } catch (_: IllegalArgumentException) {
        null
    }

/**
 * Encodes this [GatewaySection] to a [JsonElement] suitable for use as a config patch body.
 *
 * @return Serialized [JsonElement].
 */
fun GatewaySection.toJsonElement(): JsonElement =
    configJson.encodeToJsonElement(this)
