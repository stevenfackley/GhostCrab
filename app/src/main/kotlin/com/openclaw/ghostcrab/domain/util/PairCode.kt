package com.openclaw.ghostcrab.domain.util

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/** Longest scanned or deep-linked payload [decodePairCode] accepts; longer input is rejected before decoding. */
const val MAX_PAIR_CODE_LENGTH: Int = 4096

private const val PAIR_SCHEME = "ghostcrab:"
private const val PAIR_PREFIX = "ghostcrab://pair?"
private const val PAIR_VERSION = 1
private const val SETUP_CODE_SCHEME = "oc-pair://"
private val BASE64URL_TEXT = Regex("^[A-Za-z0-9_-]+={0,2}$")
private val pairJson = Json {
    ignoreUnknownKeys = true
    isLenient = false
    coerceInputValues = false
}

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
     * A plain `http(s)` or `ws(s)` gateway URL (legacy / tunnel QR codes), as its `http(s)` base URL.
     *
     * @property url The parsed, normalised URL.
     */
    data class PlainUrl(val url: GatewayUrl) : PairCodeResult

    /**
     * An `openclaw qr` setup code. It pairs OpenClaw node apps and cannot authorize an operator client such as
     * GhostCrab (ADR-0007), so it is only explained (see [setupCodeExplanation]). Its bootstrap token is not kept.
     *
     * @property url The gateway URL inside the code as an `http(s)` base URL, or `null` when it has none usable.
     */
    data class SetupCode(val url: GatewayUrl?) : PairCodeResult

    /**
     * Unusable payload.
     *
     * @property reason User-facing explanation.
     */
    data class Invalid(val reason: String) : PairCodeResult
}

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

/** The two `openclaw qr` setup-code fields GhostCrab reads; the token only proves it is a setup code. */
@Serializable
private data class SetupCodePayload(
    val url: String? = null,
    val bootstrapToken: String? = null,
) {
    override fun toString(): String =
        "SetupCodePayload(url=$url, bootstrapToken=${bootstrapToken?.let { REDACTED_PLACEHOLDER }})"
}

/**
 * Decodes [raw] from a QR code or a `VIEW` intent: a `ghostcrab://pair?p=…` bundle, a plain `http(s)` / `ws(s)`
 * gateway URL, or an `openclaw qr` setup code (bare base64url or `oc-pair://<code>`), which is only recognised.
 * URL validation is delegated to [GatewayUrl.parse] and [GatewayUrl.fromWebSocketUrl]. Input longer than
 * [MAX_PAIR_CODE_LENGTH] is rejected without decoding.
 *
 * @param raw Scanned text or deep-link URI string.
 * @return [PairCodeResult.Bundle], [PairCodeResult.PlainUrl], [PairCodeResult.SetupCode] or
 *   [PairCodeResult.Invalid]; never throws.
 */
fun decodePairCode(raw: String): PairCodeResult {
    val trimmed = raw.trim()
    return when {
        raw.length > MAX_PAIR_CODE_LENGTH ->
            PairCodeResult.Invalid("Pairing data is too large (over $MAX_PAIR_CODE_LENGTH characters)")
        // Every ghostcrab: link is pairing data, so a broken link never gets the plain-URL message.
        trimmed.startsWith(PAIR_SCHEME, ignoreCase = true) -> decodeBundle(trimmed)
        trimmed.startsWith(SETUP_CODE_SCHEME, ignoreCase = true) ->
            decodeSetupCode(trimmed.substring(SETUP_CODE_SCHEME.length))
                ?: PairCodeResult.Invalid("OpenClaw setup code could not be decoded")
        else -> GatewayUrl.parse(trimmed)?.let { PairCodeResult.PlainUrl(it) }
            ?: GatewayUrl.fromWebSocketUrl(trimmed)?.let { PairCodeResult.PlainUrl(it) }
            ?: trimmed.takeIf { BASE64URL_TEXT.matches(it) }?.let { decodeSetupCode(it) }
            ?: PairCodeResult.Invalid("QR code doesn't contain a valid gateway URL")
    }
}

/** @return The setup code's gateway URL, or `null` when [encoded] is not a setup code (no bootstrap token). */
private fun decodeSetupCode(encoded: String): PairCodeResult.SetupCode? {
    val payload = decodeBase64Text(encoded)?.let { decodeOrNull(SetupCodePayload.serializer(), it) }
    if (payload == null || payload.bootstrapToken.isNullOrBlank()) return null
    return PairCodeResult.SetupCode(payload.url?.let { GatewayUrl.fromWebSocketUrl(it) ?: GatewayUrl.parse(it) })
}

/**
 * User-facing explanation for a scanned or pasted setup code (ADR-0007): what it is, why GhostCrab cannot use it,
 * and what to do instead.
 *
 * @param url The gateway URL inside the code, when usable.
 * @return One paragraph naming the URL (when known), the gateway token and the approval command.
 */
fun setupCodeExplanation(url: GatewayUrl?): String =
    "This is an OpenClaw setup code (`openclaw qr`). Setup codes pair OpenClaw node apps; they cannot give " +
        "GhostCrab operator access (over plain ws:// they grant no access at all). Open Manual Entry, enter " +
        (url?.let { "$it" } ?: "the gateway URL") + " and the gateway token, then approve this device on the " +
        "gateway host with `openclaw devices approve <requestId>`."

private fun decodeBundle(uri: String): PairCodeResult {
    val json = payloadJson(uri)
    val version = json?.let { readVersion(it) }
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
    return encoded?.let { decodeBase64Text(it) }
}

/** Decodes URL-safe base64, falling back to standard base64 (`+` and `/`), as iOS does. */
private fun decodeBase64Text(encoded: String): String? =
    runCatching { String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8) }.getOrNull()
        ?: runCatching { String(Base64.getDecoder().decode(encoded), Charsets.UTF_8) }.getOrNull()

/**
 * The schema version, read first so a newer version is reported as such even if its shape differs.
 * Strict: only a JSON number counts; a quoted `"1"` is not a version.
 */
private fun readVersion(json: String): Int? = runCatching {
    val v = pairJson.parseToJsonElement(json).jsonObject["v"]?.jsonPrimitive
    v?.takeIf { !it.isString }?.intOrNull
}.getOrNull()

private fun <T> decodeOrNull(deserializer: DeserializationStrategy<T>, json: String): T? =
    runCatching { pairJson.decodeFromString(deserializer, json) }.getOrNull()
