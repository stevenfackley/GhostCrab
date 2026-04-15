package com.openclaw.ghostcrab.domain.model

import kotlinx.serialization.Serializable

/**
 * A language model known to the OpenClaw Gateway.
 *
 * @param id Unique identifier used by the gateway (e.g. `"gpt-4o"`).
 * @param provider Provider name (e.g. `"openai"`, `"anthropic"`).
 * @param displayName Human-readable label for UI.
 * @param isActive Whether this model is currently the active/default one.
 * @param status Operational status from the gateway (e.g. `"ready"`, `"auth-error"`).
 * @param capabilities Capability keys (e.g. `"vision"`, `"function-calling"`).
 */
@Serializable
data class ModelInfo(
    val id: String,
    val provider: String,
    val displayName: String,
    val isActive: Boolean,
    val status: String,
    val capabilities: List<String>,
)
