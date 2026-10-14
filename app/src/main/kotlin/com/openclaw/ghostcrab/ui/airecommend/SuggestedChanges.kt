package com.openclaw.ghostcrab.ui.airecommend

import com.openclaw.ghostcrab.domain.model.SuggestedChange
import com.openclaw.ghostcrab.domain.util.REDACTED_PLACEHOLDER
import com.openclaw.ghostcrab.domain.util.isSecretKey
import com.openclaw.ghostcrab.domain.util.isSecurityAffectingConfigPath
import com.openclaw.ghostcrab.domain.util.redactSecrets
import com.openclaw.ghostcrab.domain.util.withLeaf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * `true` when applying this change can weaken gateway security; the UI must confirm it explicitly.
 *
 * Checks the change's own path and, when the suggested value is an object, every nested key joined
 * to that path — so `gateway.http = {"host":"0.0.0.0"}` is caught like `gateway.http.host`.
 * A JSON `null` anywhere in the value is an RFC 7396 merge-patch delete, and deleting config
 * always needs confirmation, whatever the path.
 */
internal val SuggestedChange.isSecurityAffecting: Boolean
    get() {
        val path = "$section.$key"
        val value = parseSuggestedValue(suggestedValue)
        return value.anyPrimitive { it is JsonNull } || isSecurityAffectingConfigPath(path) ||
            value.nestedKeyPaths(path).any(::isSecurityAffectingConfigPath)
    }

/**
 * `false` when the suggestion echoes [REDACTED_PLACEHOLDER] back — anywhere in the raw value or in
 * any string leaf of its parsed JSON. The AI only ever saw redacted secrets, so applying such a
 * value would overwrite a real secret with the placeholder (e.g. lock the user out of the gateway).
 * Non-applicable suggestions must never be offered or written.
 */
internal val SuggestedChange.isApplicable: Boolean
    get() = REDACTED_PLACEHOLDER !in suggestedValue &&
        !parseSuggestedValue(suggestedValue).anyPrimitive { it.isString && REDACTED_PLACEHOLDER in it.content }

/**
 * Parses an AI-suggested value as JSON, falling back to a JSON string so a malformed suggestion
 * never crashes the apply flow. A bare word such as `gpt-4o` (which the lenient tree parser would
 * otherwise turn into an unquoted literal and emit as invalid JSON) also becomes a string.
 *
 * @param value JSON-encoded value from the AI response.
 * @return Parsed element, or a string primitive when [value] is not a valid JSON value.
 */
internal fun parseSuggestedValue(value: String): JsonElement {
    val parsed = runCatching { Json.parseToJsonElement(value) }.getOrNull()
    val isBareWord = parsed is JsonPrimitive && !parsed.isString && parsed !is JsonNull &&
        parsed.booleanOrNull == null && parsed.doubleOrNull == null
    return if (parsed == null || isBareWord) JsonPrimitive(value) else parsed
}

/**
 * Text to render for a suggested value at [path]. Secrets are never shown in clear.
 *
 * Returns [REDACTED_PLACEHOLDER] when any dotted segment of [path] is a secret key (see
 * [isSecretKey]). Otherwise the value is parsed and passed through [redactSecrets]: if nothing was
 * redacted, [suggestedValue] is returned exactly as the AI sent it; if something was, the redacted
 * JSON (or the bare placeholder, for a string value) is returned.
 *
 * @param path Dotted config path, e.g. `"gateway.auth.token"`.
 * @param suggestedValue JSON-encoded value from the AI response.
 * @return Display-safe text for [suggestedValue].
 */
internal fun displayValueFor(path: String, suggestedValue: String): String {
    if (path.split('.').any(::isSecretKey)) return REDACTED_PLACEHOLDER
    val parsed = parseSuggestedValue(suggestedValue)
    val redacted = parsed.redactSecrets()
    return when {
        redacted == parsed -> suggestedValue
        redacted is JsonPrimitive && redacted.isString -> redacted.content
        else -> redacted.toString()
    }
}

/**
 * Groups [changes] into one RFC 7396 merge-patch per top-level section. Dotted keys become nested
 * objects (`"http.port"` → `{"http":{"port":…}}`), so the gateway never receives a literal
 * `"http.port"` key. Later changes to the same leaf win.
 *
 * @param changes Changes the user selected.
 * @return Section key → merge-patch object, in first-seen section order.
 */
internal fun sectionPatches(changes: Collection<SuggestedChange>): Map<String, JsonObject> =
    changes.groupBy { it.section }.mapValues { (_, sectionChanges) ->
        sectionChanges.fold(JsonObject(emptyMap())) { patch, change ->
            patch.withLeaf(change.key.split('.'), parseSuggestedValue(change.suggestedValue))
        }
    }

private fun JsonElement.nestedKeyPaths(prefix: String): List<String> = when (this) {
    is JsonObject -> entries.flatMap { (key, value) ->
        val path = "$prefix.$key"
        listOf(path) + value.nestedKeyPaths(path)
    }
    is JsonArray -> flatMap { it.nestedKeyPaths(prefix) }
    is JsonPrimitive -> emptyList()
}

/** `true` if any primitive leaf (`null` included) of this element satisfies [predicate]. */
private fun JsonElement.anyPrimitive(predicate: (JsonPrimitive) -> Boolean): Boolean = when (this) {
    is JsonObject -> values.any { it.anyPrimitive(predicate) }
    is JsonArray -> any { it.anyPrimitive(predicate) }
    is JsonPrimitive -> predicate(this)
}
