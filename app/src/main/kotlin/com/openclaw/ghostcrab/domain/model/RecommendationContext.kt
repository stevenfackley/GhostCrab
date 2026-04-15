package com.openclaw.ghostcrab.domain.model

/**
 * Context sent alongside an AI recommendation query.
 *
 * Auto-collected from the current session; never includes secrets.
 *
 * @param activeConfig The gateway's current config (used for grounding suggestions).
 * @param hardwareInfo Optional hardware description from the gateway's `/status` (e.g. RAM, GPU).
 * @param activeModelId ID of the currently active model, if known.
 */
data class RecommendationContext(
    val activeConfig: OpenClawConfig,
    val hardwareInfo: String?,
    val activeModelId: String?,
)
