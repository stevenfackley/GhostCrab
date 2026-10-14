package com.openclaw.ghostcrab.domain.util

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

/** Longest scanned or deep-linked payload [decodePairCode] accepts; longer input is rejected before decoding. */
const val MAX_PAIR_CODE_LENGTH: Int = 4096

private const val PAIR_SCHEME = "ghostcrab:"
private const val PAIR_PREFIX = "ghostcrab://pair?"
private const val PAIR_VERSION = 1
private val pairJson = Json { ignoreUnknownKeys = true }

/**
 * Connection bundle carried by a `ghostcrab://pair?p=<base64url(json)>` code (schema v1, see
 * `docs/superpowers/specs/2026-04-17-qr-pairing-repurpose-design.md`).
 *
 * @property url Validated gateway base URL.
 * @property token Bearer token, or `null` for unauthenticated gateways. Sensitive — never log.
 * @property name Optional display name.
 */
data class PairBundle(val url: GatewayUrl, val token: String?, val name: String?) {
    /** Redacts [token]. */
    override fun toString(): String =
        "PairBundle(url=$url, token=${token?.let { REDACTED_PLACEHOLDER }}, name=$name)"
}

/** Result of decoding a scanned or deep-linked payload. Decoding never throws. */
sealed interface PairCodeResult {
    /**
     * A full `ghostcrab://pair` bundle.
     *
     * @property bundle The decoded bundle; its `toString` redacts the token.
     */
    data class Bundle(val bundle: PairBundle) : PairCodeResult

    /**
     * A plain `http(s)` gateway URL (legacy / tunnel QR codes).
     *
     * @property url The parsed, normalised URL.
     */
    data class PlainUrl(val url: GatewayUrl) : PairCodeResult

    /**
     * Unusable payload.
     *
     * @property reason User-facing explanation.
     */
    data class Invalid(val reason: String) : PairCodeResult
}

/** Just the schema version, read first so a newer version is reported as such even if its shape differs. */
@Serializable
private data class PairHeader(val v: Int)

@Serializable
private data class PairPayloadV1(
    val v: Int,
    val url: String? = null,
    val token: String? = null,
    val name: String? = null,
) {
    /** Redacts [token]: the payload is never logged, but keep a stray `toString` from leaking the bearer. */
    override fun toString(): String = "PairPayloadV1(v=$v, url=$url, token=${token?.let { REDACTED_PLACEHOLDER }})"
}

/**
 * Decodes [raw] from a QR code or a `VIEW` intent: a `ghostcrab://pair?p=…` bundle or a plain
 * `http(s)` gateway URL. URL validation is delegated to [GatewayUrl.parse]. Input longer than
 * [MAX_PAIR_CODE_LENGTH] is rejected without decoding.
 *
 * @param raw Scanned text or deep-link URI string.
 * @return [PairCodeResult.Bundle], [PairCodeResult.PlainUrl], or [PairCodeResult.Invalid]; never throws.
 */
fun decodePairCode(raw: String): PairCodeResult {
    val trimmed = raw.trim()
    return when {
        raw.length > MAX_PAIR_CODE_LENGTH ->
            PairCodeResult.Invalid("Pairing data is too large (over $MAX_PAIR_CODE_LENGTH characters)")
        // Every ghostcrab: link is pairing data, so a broken link never gets the plain-URL message.
        trimmed.startsWith(PAIR_SCHEME, ignoreCase = true) -> decodeBundle(trimmed)
        else -> GatewayUrl.parse(trimmed)?.let { PairCodeResult.PlainUrl(it) }
            ?: PairCodeResult.Invalid("QR code doesn't contain a valid gateway URL")
    }
}

private fun decodeBundle(uri: String): PairCodeResult {
    val json = payloadJson(uri)
    val version = json?.let { decodeOrNull(PairHeader.serializer(), it) }?.v
    val payload = json?.takeIf { version == PAIR_VERSION }?.let { decodeOrNull(PairPayloadV1.serializer(), it) }
    val url = payload?.url?.let { GatewayUrl.parse(it) }
    return when {
        version == null -> PairCodeResult.Invalid("Pairing data could not be decoded")
        version != PAIR_VERSION -> PairCodeResult.Invalid("Unsupported pairing data version $version")
        payload == null -> PairCodeResult.Invalid("Pairing data could not be decoded")
        url == null -> PairCodeResult.Invalid("Pairing data has no valid http(s) gateway URL")
        else -> PairCodeResult.Bundle(PairBundle(url, payload.token?.takeIf { it.isNotBlank() }, payload.name))
    }
}

/** The base64url-decoded `p` query parameter of a `ghostcrab://pair?` link, or `null`. */
private fun payloadJson(uri: String): String? {
    val encoded = uri.takeIf { it.startsWith(PAIR_PREFIX, ignoreCase = true) }
        ?.substringAfter('?')?.split('&')?.firstOrNull { it.startsWith("p=") }?.removePrefix("p=")
    return encoded?.let { runCatching { String(Base64.getUrlDecoder().decode(it), Charsets.UTF_8) }.getOrNull() }
}

private fun <T> decodeOrNull(deserializer: DeserializationStrategy<T>, json: String): T? =
    runCatching { pairJson.decodeFromString(deserializer, json) }.getOrNull()
