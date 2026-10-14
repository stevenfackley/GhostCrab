package com.openclaw.ghostcrab.domain.util

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Returns a copy of this JSON with the leaf at [path] set to [value]. Every other key — including
 * keys no typed model knows about — is preserved verbatim and in order; missing intermediate
 * objects are created; nothing is filled with defaults. A non-object receiver is treated as `{}`.
 *
 * @param path Keys from the receiver's root, e.g. `listOf("http", "port")`. Must not be empty.
 * @param value New leaf value.
 * @return The updated object.
 * @throws IllegalArgumentException if [path] is empty.
 */
fun JsonElement.withLeaf(path: List<String>, value: JsonElement): JsonObject {
    require(path.isNotEmpty()) { "path must not be empty" }
    val obj = this as? JsonObject ?: JsonObject(emptyMap())
    val head = path.first()
    val next = if (path.size == 1) value else (obj[head] ?: JsonObject(emptyMap())).withLeaf(path.drop(1), value)
    return JsonObject(obj + (head to next))
}
