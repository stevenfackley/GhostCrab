package com.openclaw.ghostcrab.domain.repository

import com.openclaw.ghostcrab.domain.exception.AIQuotaExceededException
import com.openclaw.ghostcrab.domain.exception.AIServiceUnavailableException
import com.openclaw.ghostcrab.domain.model.AIRecommendation
import com.openclaw.ghostcrab.domain.model.RecommendationContext

/**
 * Submits AI recommendation queries through the gateway's proxied CLI skill.
 *
 * The gateway must have the `skill-ai-recommend` capability; check [isAvailable] first.
 *
 * **Contract frozen at v1.0.**
 */
interface AIRecommendationService {

    /**
     * Returns whether the connected gateway has the AI recommendation skill installed.
     *
     * Probes the gateway's capability list; does not make an AI call.
     *
     * @return `true` if the skill is present and responding.
     */
    suspend fun isAvailable(): Boolean

    /**
     * Submits a recommendation query to the gateway's AI skill.
     *
     * The [context] is auto-collected and attached to the request body. It never contains
     * secrets (tokens are stripped before serialization).
     *
     * @param query Free-text user query (e.g. "best coding model for 16GB RAM").
     * @param context Current session context for grounding the recommendation.
     * @return The [AIRecommendation] containing the response and optional suggested changes.
     * @throws AIServiceUnavailableException if the skill is not present on the gateway.
     * @throws AIQuotaExceededException if the rate limit is exceeded.
     */
    suspend fun getRecommendation(query: String, context: RecommendationContext): AIRecommendation
}
