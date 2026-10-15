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
 * Maps a rejected `config.patch` of one top-level [section] onto the frozen `ConfigRepository` contract:
 * - a stale, unknown or missing `baseHash` → 412-style [GatewayApiException] (callers reload);
 * - schema validation with exactly one issue, inside [section] (`details.issues[{path, message}]` with a path
 *   `<section>.…`) → [ConfigValidationException] for that path, so the editor can show it on the field;
 * - any other `INVALID_REQUEST` → 422-style [GatewayApiException] whose body is the server text plus every issue it
 *   does not already spell out. That covers issues outside [section] (`config.patch` validates the whole merged
 *   config, so a key the user never touched can fail the write), several issues, a JSON5 syntax error and array
 *   removal without `replacePaths`;
 * - anything else passes through.
 *
 * @param section The top-level key the write patched (e.g. `gateway`).
 * @return The exception to throw.
 */
internal fun GatewayRpcException.toConfigWriteException(section: String): GatewayException =
    mapConfigWrite(inlineScope = "$section.")

/**
 * Maps a rejected `config.patch` of the active model (`agents.defaults.model.primary`). No form field shows the
 * value, so a validation failure is always a 422-style [GatewayApiException] listing every issue; a stale or
 * missing `baseHash` is the same 412-style exception the config editor reloads on.
 *
 * @return The exception to throw.
 */
internal fun GatewayRpcException.toModelWriteException(): GatewayException = mapConfigWrite(inlineScope = null)

/** @param inlineScope Path prefix whose single issue becomes a [ConfigValidationException]; `null` for none. */
private fun GatewayRpcException.mapConfigWrite(inlineScope: String?): GatewayException = when {
    isStaleBaseHash() -> GatewayApiException(url, STATUS_PRECONDITION_FAILED, rpcMessage)
    code == CODE_INVALID_REQUEST -> {
        val issues = issues()
        val only = issues.singleOrNull()
        if (inlineScope != null && only?.path != null && only.path.startsWith(inlineScope)) {
            ConfigValidationException(only.path, only.message)
        } else {
            GatewayApiException(url, STATUS_UNPROCESSABLE, withIssues(issues))
        }
    }
    else -> this
}

private fun GatewayRpcException.isStaleBaseHash(): Boolean =
    code == CODE_INVALID_REQUEST && STALE_HASH_PREFIXES.any { rpcMessage.startsWith(it) }

/** One `details.issues[]` entry (e.g. `gateway.port` → `Too big: …`); the path is optional upstream. */
private class ConfigIssue(val path: String?, val message: String) {
    override fun toString(): String = path?.let { "$it: $message" } ?: message
}

/** `details.issues[]` entries that carry a message, in server order. */
private fun GatewayRpcException.issues(): List<ConfigIssue> =
    (details?.get("issues") as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { issue ->
        issue.stringOrNull("message")?.takeIf { it.isNotBlank() }?.let { message ->
            ConfigIssue(issue.stringOrNull("path")?.takeIf { it.isNotBlank() }, message)
        }
    }

/** The server message plus every issue it does not already spell out, `; `-separated. */
private fun GatewayRpcException.withIssues(issues: List<ConfigIssue>): String =
    (listOf(rpcMessage) + issues.map { it.toString() }.filterNot { it in rpcMessage }).joinToString("; ")
