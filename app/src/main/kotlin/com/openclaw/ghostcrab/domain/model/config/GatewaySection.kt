package com.openclaw.ghostcrab.domain.model.config

import com.openclaw.ghostcrab.domain.util.DEFAULT_GATEWAY_PORT
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** Lenient [Json] instance used for round-tripping config sections. */
private val configJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Typed view of the top-level `gateway` section of `openclaw.json` (upstream 2026.10.3, `config.get` →
 * `parsed.gateway`, ADR-0007). Unknown keys are ignored here; the editor preserves them by rewriting single leaves.
 *
 * @param mode `local` or `remote`; `null` when unset.
 * @param port Listening port, 1–65535 (the gateway's default when absent).
 * @param bind `loopback`, `lan`, `tailnet`, `auto`, … (kept as text: the set grows upstream); `null` when unset.
 * @param auth Authentication settings.
 */
@Serializable
data class GatewaySection(
    val mode: String? = null,
    val port: Int = DEFAULT_GATEWAY_PORT,
    val bind: String? = null,
    val auth: GatewayAuthSection = GatewayAuthSection(),
)

/**
 * Decodes this [JsonElement] into a [GatewaySection].
 *
 * @return The parsed section.
 * @throws IllegalArgumentException (incl. `SerializationException`) when the JSON does not fit the model, e.g. an
 *   unknown `auth.mode`. UI code should use [toGatewaySectionOrNull].
 */
fun JsonElement.toGatewaySection(): GatewaySection = configJson.decodeFromJsonElement(this)

/**
 * Decodes this [JsonElement] into a [GatewaySection], or returns `null` when the JSON cannot be represented by the
 * typed model. Callers then fall back to raw JSON editing — never to defaults, which would misrepresent the
 * gateway's real settings.
 *
 * @return The parsed section, or `null` when decoding fails (`SerializationException` is an
 *   [IllegalArgumentException]).
 */
fun JsonElement.toGatewaySectionOrNull(): GatewaySection? =
    try {
        configJson.decodeFromJsonElement<GatewaySection>(this)
    } catch (_: IllegalArgumentException) {
        null
    }

/**
 * Encodes this [GatewaySection] to JSON (default values omitted).
 *
 * @return The section as a [JsonElement].
 */
fun GatewaySection.toJsonElement(): JsonElement = configJson.encodeToJsonElement(this)
