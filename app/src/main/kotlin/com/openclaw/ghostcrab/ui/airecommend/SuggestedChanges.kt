package com.openclaw.ghostcrab.ui.airecommend

import com.openclaw.ghostcrab.domain.model.SuggestedChange
import com.openclaw.ghostcrab.domain.util.isSecurityAffectingConfigPath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** `true` when applying this change can weaken gateway security; the UI must confirm it explicitly. */
internal val SuggestedChange.isSecurityAffecting: Boolean
    get() = isSecurityAffectingConfigPath("$section.$key")

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
