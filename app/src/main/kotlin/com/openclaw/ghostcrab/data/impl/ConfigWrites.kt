package com.openclaw.ghostcrab.data.impl

import com.openclaw.ghostcrab.data.gateway.stringOrNull
import com.openclaw.ghostcrab.domain.exception.ConfigValidationException
import com.openclaw.ghostcrab.domain.exception.GatewayApiException
import com.openclaw.ghostcrab.domain.exception.GatewayException
import com.openclaw.ghostcrab.domain.exception.GatewayRpcException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `config.patch` answers once the gateway applied the write (hot reload), which can take a while. */
internal const val CONFIG_PATCH_TIMEOUT_MS: Long = 60_000L

private const val STATUS_PRECONDITION_FAILED = 412
private const val STATUS_UNPROCESSABLE = 422
private const val CODE_INVALID_REQUEST = "INVALID_REQUEST"

/**
 * @param patch JSON merge patch (objects merge, `null` deletes), e.g. `{"gateway":{"port":18790}}`.
 * @param baseHash `config.get.hash` the patch was made against; required once a config file exists.
 * @return `config.patch` params; `raw` is the patch as JSON text (JSON is valid JSON5).
 */
internal fun configPatchParams(patch: JsonObject, baseHash: String?): JsonObject = buildJsonObject {
    put("raw", patch.toString())
    baseHash?.let { put("baseHash", it) }
}

/**
 * Messages of the two `baseHash` rejections (verified against 2026.10.3; neither carries `details`):
 * `config changed since last load; re-run config.get and retry` and
 * `config base hash required for <path>; re-run config.get and retry with baseHash`.
 */
private val STALE_HASH_PREFIXES = listOf("config changed since last load", "config base hash required")

/**
 * Maps a rejected `config.patch` onto the frozen `ConfigRepository` contract:
 * - a stale, unknown or missing `baseHash` → 412-style [GatewayApiException] (callers reload);
 * - schema validation (`INVALID_REQUEST` with `details.issues[{path, message, allowedValues?}]`) →
 *   [ConfigValidationException] for the first issue, so the editor shows it inline on that field;
 * - any other `INVALID_REQUEST` (a JSON5 syntax error, array removal without `replacePaths`, issues without a
 *   path and message) → 422-style [GatewayApiException] carrying the server text;
 * - anything else passes through.
 *
 * @return The exception to throw.
 */
internal fun GatewayRpcException.toConfigWriteException(): GatewayException = when {
    isStaleBaseHash() -> GatewayApiException(url, STATUS_PRECONDITION_FAILED, rpcMessage)
    code == CODE_INVALID_REQUEST ->
        firstIssue() ?: GatewayApiException(url, STATUS_UNPROCESSABLE, rpcMessage)
    else -> this
}

private fun GatewayRpcException.isStaleBaseHash(): Boolean =
    code == CODE_INVALID_REQUEST && STALE_HASH_PREFIXES.any { rpcMessage.startsWith(it) }

/** `details.issues[0]` as a field error (e.g. `gateway.port` → `Too big: …`), or `null` without path and message. */
private fun GatewayRpcException.firstIssue(): ConfigValidationException? {
    val issue = (details?.get("issues") as? JsonArray)?.firstOrNull() as? JsonObject
    val path = issue?.stringOrNull("path")?.takeIf { it.isNotBlank() }
    val message = issue?.stringOrNull("message")?.takeIf { it.isNotBlank() }
    return if (path != null && message != null) ConfigValidationException(path, message) else null
}
