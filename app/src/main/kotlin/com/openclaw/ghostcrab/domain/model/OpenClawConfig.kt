package com.openclaw.ghostcrab.domain.model

import kotlinx.serialization.json.JsonElement

/**
 * The full `openclaw.json` configuration as returned by the gateway.
 *
 * Sections are kept as raw [JsonElement] to avoid brittle schema lock-in.
 * Phase 6 adds typed wrappers for well-known sections (`gateway.http`, `gateway.auth`, etc.)
 * while this class remains the authoritative wire type.
 *
 * @param sections Top-level config keys mapped to their raw JSON values.
 * @param etag Server-side version tag for optimistic-concurrency detection. May be `null` if
 *   the gateway does not support ETags.
 */
data class OpenClawConfig(
    val sections: Map<String, JsonElement>,
    val etag: String? = null,
)
