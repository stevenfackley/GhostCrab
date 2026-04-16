package com.openclaw.ghostcrab.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Wire type for a POST `/api/ai/recommend` request body.
 *
 * @param query Free-text user query.
 * @param context Auto-collected session context sent for grounding.
 */
@Serializable
data class AIRecommendationRequestDto(
    val query: String,
    val context: AIContextDto,
)

/**
 * Session context serialized alongside the AI query.
 *
 * Never includes secrets — tokens are stripped before serialization.
 *
 * @param activeConfig Current gateway config as a raw JSON object. `null` if unavailable.
 * @param hardwareInfo Hardware description from the gateway's `/status` response. May be `null`.
 * @param activeModelId ID of the currently active model, if known.
 */
@Serializable
data class AIContextDto(
    @SerialName("active_config") val activeConfig: JsonObject?,
    @SerialName("hardware_info") val hardwareInfo: String?,
    @SerialName("active_model_id") val activeModelId: String?,
)

/**
 * Wire type for a POST `/api/ai/recommend` response body.
 *
 * @param recommendation Free-text recommendation from the gateway's AI skill.
 * @param suggestedChanges Structured config changes the AI suggests. May be empty.
 */
@Serializable
data class AIRecommendationResponseDto(
    val recommendation: String,
    @SerialName("suggested_changes") val suggestedChanges: List<SuggestedChangeDto> = emptyList(),
)

/**
 * A single structured config change suggested by the AI.
 *
 * @param section Top-level config section key (e.g. `"models"`).
 * @param key Nested key within the section (e.g. `"timeout_ms"`).
 * @param currentValue Current value as a JSON string representation. `null` if unknown.
 * @param suggestedValue Proposed new value as a JSON-encoded string.
 * @param rationale Short explanation for the change.
 */
@Serializable
data class SuggestedChangeDto(
    val section: String,
    val key: String,
    @SerialName("current_value") val currentValue: String? = null,
    @SerialName("suggested_value") val suggestedValue: String,
    val rationale: String = "",
)
