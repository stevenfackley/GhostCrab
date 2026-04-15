package com.openclaw.ghostcrab.data.api.dto

import kotlinx.serialization.Serializable

/**
 * Wire-type DTO for a single model entry returned by `GET /api/models/status`.
 *
 * All fields except [id] and [provider] are optional with sensible defaults so
 * the client tolerates partially-populated gateway responses.
 *
 * @param id Unique model identifier (e.g. `"gpt-4o"`).
 * @param provider Provider slug (e.g. `"openai"`, `"anthropic"`).
 * @param displayName Human-readable label. Defaults to [id] when absent.
 * @param isActive Whether this model is currently the active/default model.
 * @param status Lifecycle status string: `"ready"`, `"auth-error"`, `"loading"`, etc.
 * @param capabilities List of capability tags reported by the gateway.
 */
@Serializable
public data class ModelDto(
    val id: String,
    val provider: String,
    val displayName: String = id,
    val isActive: Boolean = false,
    val status: String = "unknown",
    val capabilities: List<String> = emptyList(),
)
