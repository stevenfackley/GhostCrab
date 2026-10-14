package com.openclaw.ghostcrab.domain.util

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

private const val PAIR_PREFIX = "ghostcrab://pair?"
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

@Serializable
private data class PairPayloadV1(val v: Int, val url: String, val token: String? = null, val name: String? = null) {
    /** Redacts [token]: the payload is never logged, but keep a stray `toString` from leaking the bearer. */
    override fun toString(): String = "PairPayloadV1(v=$v, url=$url, token=${token?.let { REDACTED_PLACEHOLDER }})"
}

/**
 * Decodes [raw] from a QR code or a `VIEW` intent: a `ghostcrab://pair?p=…` bundle or a plain
 * `http(s)` gateway URL. URL validation is delegated to [GatewayUrl.parse].
 *
 * @param raw Scanned text or deep-link URI string.
 * @return [PairCodeResult.Bundle], [PairCodeResult.PlainUrl], or [PairCodeResult.Invalid]; never throws.
 */
fun decodePairCode(raw: String): PairCodeResult {
    val trimmed = raw.trim()
    return if (trimmed.startsWith(PAIR_PREFIX, ignoreCase = true)) {
        decodeBundle(trimmed)
    } else {
        GatewayUrl.parse(trimmed)?.let { PairCodeResult.PlainUrl(it) }
            ?: PairCodeResult.Invalid("QR code doesn't contain a valid gateway URL")
    }
}

private fun decodeBundle(uri: String): PairCodeResult {
    val encoded = uri.substringAfter('?').split('&').firstOrNull { it.startsWith("p=") }?.removePrefix("p=")
    val payload = encoded
        ?.let { runCatching { String(Base64.getUrlDecoder().decode(it), Charsets.UTF_8) }.getOrNull() }
        ?.let { runCatching { pairJson.decodeFromString(PairPayloadV1.serializer(), it) }.getOrNull() }
    val url = payload?.let { GatewayUrl.parse(it.url) }
    return when {
        payload == null -> PairCodeResult.Invalid("Pair code could not be decoded")
        payload.v != 1 -> PairCodeResult.Invalid("Unsupported pair code version ${payload.v}")
        url == null -> PairCodeResult.Invalid("Pair code URL is not a valid http(s) gateway URL")
        else -> PairCodeResult.Bundle(PairBundle(url, payload.token?.takeIf { it.isNotBlank() }, payload.name))
    }
}
