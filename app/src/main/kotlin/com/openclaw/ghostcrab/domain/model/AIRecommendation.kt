package com.openclaw.ghostcrab.domain.model

/**
 * A recommendation returned by the AI skill on the gateway.
 *
 * @param query The original user query.
 * @param recommendation Free-text recommendation from the AI.
 * @param suggestedChanges Structured config changes the AI suggests applying. May be empty.
 */
data class AIRecommendation(
    val query: String,
    val recommendation: String,
    val suggestedChanges: List<SuggestedChange>,
)

/**
 * A single config change suggested by the AI.
 *
 * @param section Top-level config section key (e.g. `"models"`).
 * @param key Nested key within the section.
 * @param currentValue Current value as a string representation, or `null` if unknown.
 * @param suggestedValue Proposed new value as a string representation.
 * @param rationale Short explanation for the change.
 */
data class SuggestedChange(
    val section: String,
    val key: String,
    val currentValue: String?,
    val suggestedValue: String,
    /** Empty string when the gateway omits a rationale. Never `null` — use [isNotBlank] to test. */
    val rationale: String = "",
)
