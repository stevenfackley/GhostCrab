package com.openclaw.ghostcrab.data.gateway

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** Gateway WS protocol version this client implements (`connect.minProtocol` = `maxProtocol`, ADR-0007). */
const val GATEWAY_PROTOCOL_VERSION: Int = 4

/** Codec for gateway frames. Unknown keys are normal here: every gateway release adds fields. */
internal val gatewayJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/**
 * Client → gateway request: `{"type":"req","id":"<string>","method":"…","params":{…}}`.
 *
 * @param id Unique per socket; the response echoes it.
 * @param method RPC method name, e.g. `skills.status`.
 * @param params Method parameters; omitted from the frame when `null`. `connect` params carry the gateway
 *   token and device proof, so [toString] never prints them.
 * @param type Always `req`.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RequestFrame(
    val id: String,
    val method: String,
    val params: JsonElement? = null,
    @EncodeDefault val type: String = "req",
) {
    override fun toString(): String = "RequestFrame(id=$id, method=$method, params=${redacted(params)})"
}

/**
 * `error` of a failed response (upstream `ErrorShape`).
 *
 * @param code Top-level code: `INVALID_REQUEST`, `FORBIDDEN`, `NOT_PAIRED`, `UNAVAILABLE`, ….
 * @param message Human-readable server text; shown verbatim, never parsed when [details] says more.
 * @param details Structured details; `details.code` is the machine-readable sub-code.
 * @param retryable Server hint; absent means "not retryable".
 * @param retryAfterMs Server back-off hint, if any.
 */
@Serializable
data class ErrorShape(
    val code: String,
    val message: String,
    val details: JsonElement? = null,
    val retryable: Boolean? = null,
    val retryAfterMs: Long? = null,
) {
    /** [details] when it is a JSON object, else `null`. */
    val detailsObject: JsonObject? get() = details as? JsonObject

    /** `details.code` (e.g. `PAIRING_REQUIRED`, `MISSING_SCOPE`, `AUTH_TOKEN_MISMATCH`), or `null`. */
    val detailCode: String? get() = detailsObject?.stringOrNull("code")
}

/** A decoded gateway → client frame. */
sealed interface GatewayFrame {

    /**
     * Answer to a request.
     *
     * @param id The request id it answers.
     * @param ok `true` on success.
     * @param payload Result (may also be present on failure, e.g. `skills.install`). `hello-ok` carries the
     *   issued device token, so [toString] never prints it.
     * @param error Present when [ok] is `false`.
     */
    data class Response(
        val id: String,
        val ok: Boolean,
        val payload: JsonElement?,
        val error: ErrorShape?,
    ) : GatewayFrame {
        override fun toString(): String = "Response(id=$id, ok=$ok, payload=${redacted(payload)}, error=$error)"
    }

    /**
     * Server push, e.g. `connect.challenge`, `tick`, `health`, `skills.changed`.
     *
     * @param event Event name.
     * @param payload Event body; [toString] never prints it.
     * @param seq Connection sequence number, when the server sends one.
     */
    data class Event(
        val event: String,
        val payload: JsonElement?,
        val seq: Long?,
    ) : GatewayFrame {
        override fun toString(): String = "Event(event=$event, seq=$seq, payload=${redacted(payload)})"
    }
}

/** Placeholder for a frame body in `toString`: bodies can carry tokens and device proofs. */
private fun redacted(body: JsonElement?): String = if (body == null) "null" else "[REDACTED]"

/** Encodes requests and decodes everything the gateway sends. Never throws on decode. */
object GatewayFrameCodec {

    /**
     * @param frame The request.
     * @return The JSON text to send.
     */
    fun encode(frame: RequestFrame): String = gatewayJson.encodeToString(RequestFrame.serializer(), frame)

    /**
     * @param raw One text frame from the socket.
     * @return The frame, or `null` for anything that is not a well-formed `res` / `event` frame
     *   (malformed JSON, JSON-RPC, numeric ids). The official client ignores such frames too.
     */
    fun decode(raw: String): GatewayFrame? {
        val obj = try {
            gatewayJson.parseToJsonElement(raw) as? JsonObject
        } catch (ignored: IllegalArgumentException) { // SerializationException extends it
            null
        } ?: return null
        return when (obj.stringOrNull("type")) {
            "res" -> decodeResponse(obj)
            "event" -> obj.stringOrNull("event")?.let { name ->
                GatewayFrame.Event(name, obj["payload"], (obj["seq"] as? JsonPrimitive)?.longOrNull)
            }
            else -> null
        }
    }

    private fun decodeResponse(obj: JsonObject): GatewayFrame.Response? {
        val id = obj.stringOrNull("id")
        val ok = obj.strictBooleanOrNull("ok")
        if (id == null || ok == null) return null
        val error = (obj["error"] as? JsonObject)?.let(::decodeError)
        return GatewayFrame.Response(id, ok, obj["payload"], error)
    }

    /**
     * `code` and `message` are required and must be strings; the optional fields are read leniently, so a
     * malformed hint never hides the error's code or details.
     */
    private fun decodeError(obj: JsonObject): ErrorShape {
        val code = obj.stringOrNull("code")
        val message = obj.stringOrNull("message")
        if (code == null || message == null) {
            // Never echo the raw object: it is server-controlled and may be large or sensitive.
            return ErrorShape(code = MALFORMED_ERROR_CODE, message = "error object without a string code and message")
        }
        return ErrorShape(
            code = code,
            message = message,
            details = obj["details"]?.takeUnless { it is JsonNull },
            retryable = obj.strictBooleanOrNull("retryable"),
            retryAfterMs = (obj["retryAfterMs"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull,
        )
    }

    /** [ErrorShape.code] of an `error` object this client could not read. */
    const val MALFORMED_ERROR_CODE: String = "MALFORMED_ERROR"
}

/** The JSON boolean at [key]; `null` when absent or anything else (including the strings `"true"`/`"false"`). */
private fun JsonObject.strictBooleanOrNull(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

/** The string at [key], or `null` when absent or not a JSON string. Never throws. */
internal fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The string elements of the array at [key]; other elements are skipped. Never throws. */
internal fun JsonObject.stringListOrEmpty(key: String): List<String> =
    (this[key] as? JsonArray)
        ?.mapNotNull { element -> (element as? JsonPrimitive)?.takeIf { it.isString }?.content }
        .orEmpty()

/** The object at the nested [path], or `null` as soon as a step is missing or not an object. */
internal fun JsonObject.objectAt(vararg path: String): JsonObject? =
    path.fold(this as JsonObject?) { current, key -> current?.get(key) as? JsonObject }
