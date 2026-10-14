package com.openclaw.ghostcrab.domain.util

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Returns a copy of this JSON with the leaf at [path] set to [value]. Every other key — including
 * keys no typed model knows about — is preserved verbatim and in order; missing intermediate
 * objects are created; nothing is filled with defaults. A non-object receiver is treated as `{}`.
 *
 * Edge case: an array or primitive that sits on [path] (not at its end) is **replaced** by an
 * object holding only the new branch, e.g. `{"http":[1,2]}` + `http.port = 443` gives
 * `{"http":{"port":443}}`. The edit always lands; the old non-object value at that key is lost.
 *
 * @param path Keys from the receiver's root, e.g. `listOf("http", "port")`. Must not be empty, and
 *   no key may be empty (a dotted key such as `"a..b"` or `".a"` split on `.` is rejected).
 * @param value New leaf value.
 * @return The updated object.
 * @throws IllegalArgumentException if [path] is empty or contains an empty key.
 */
fun JsonElement.withLeaf(path: List<String>, value: JsonElement): JsonObject {
    require(path.isNotEmpty()) { "config path must not be empty" }
    require(path.none { it.isEmpty() }) { "config path must not contain an empty key: ${path.joinToString(".")}" }
    val obj = this as? JsonObject ?: JsonObject(emptyMap())
    val head = path.first()
    val next = if (path.size == 1) value else (obj[head] ?: JsonObject(emptyMap())).withLeaf(path.drop(1), value)
    return JsonObject(obj + (head to next))
}
