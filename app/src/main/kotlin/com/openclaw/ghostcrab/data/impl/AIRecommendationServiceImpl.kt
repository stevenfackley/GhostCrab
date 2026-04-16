package com.openclaw.ghostcrab.data.impl

import com.openclaw.ghostcrab.data.api.dto.AIContextDto
import com.openclaw.ghostcrab.data.api.dto.AIRecommendationRequestDto
import com.openclaw.ghostcrab.domain.exception.AIQuotaExceededException
import com.openclaw.ghostcrab.domain.exception.AIServiceUnavailableException
import com.openclaw.ghostcrab.domain.exception.GatewayApiException
import com.openclaw.ghostcrab.domain.model.AIRecommendation
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import com.openclaw.ghostcrab.domain.model.RecommendationContext
import com.openclaw.ghostcrab.domain.model.SuggestedChange
import com.openclaw.ghostcrab.domain.repository.AIRecommendationService
import kotlinx.serialization.json.JsonObject

/**
 * Ktor-backed [AIRecommendationService] that proxies queries to the gateway's AI skill.
 *
 * [isAvailable] is a capability check against the cached connection state — no network call.
 * [getRecommendation] POSTs to `POST /api/ai/recommend` on the connected gateway.
 *
 * @param connectionManager Provides the active [OpenClawApiClient] and connection state.
 */
class AIRecommendationServiceImpl(
    private val connectionManager: GatewayConnectionManagerImpl,
) : AIRecommendationService {

    /**
     * Returns `true` if the currently-connected gateway advertises the `skill-ai-recommend`
     * capability in its `/status` response.
     *
     * Reads the in-memory connection state — no network call is made.
     *
     * @return `true` when [GatewayConnection.Connected.capabilities] contains `"skill-ai-recommend"`.
     */
    override suspend fun isAvailable(): Boolean {
        val state = connectionManager.connectionState.value
        return state is GatewayConnection.Connected &&
            SKILL_KEY in state.capabilities
    }

    /**
     * Submits [query] and auto-collected [context] to `POST /api/ai/recommend`.
     *
     * @param query Free-text user query.
     * @param context Session context assembled by the ViewModel (config + hardware + active model).
     * @return [AIRecommendation] mapped from the gateway DTO.
     * @throws AIServiceUnavailableException if the gateway returns 404 (skill not installed).
     * @throws AIQuotaExceededException if the gateway returns 429 (rate limit).
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayException on other network errors.
     * @throws IllegalStateException if no active connection exists.
     */
    override suspend fun getRecommendation(
        query: String,
        context: RecommendationContext,
    ): AIRecommendation {
        val client = connectionManager.requireClient()
        val request = AIRecommendationRequestDto(
            query = query,
            context = AIContextDto(
                activeConfig = JsonObject(context.activeConfig.sections),
                hardwareInfo = context.hardwareInfo,
                activeModelId = context.activeModelId,
            ),
        )
        return try {
            client.getAIRecommendation(request).let { dto ->
                AIRecommendation(
                    query = query,
                    recommendation = dto.recommendation,
                    suggestedChanges = dto.suggestedChanges.map { change ->
                        SuggestedChange(
                            section = change.section,
                            key = change.key,
                            currentValue = change.currentValue,
                            suggestedValue = change.suggestedValue,
                            rationale = change.rationale,
                        )
                    },
                )
            }
        } catch (e: GatewayApiException) {
            when (e.statusCode) {
                404 -> throw AIServiceUnavailableException(client.baseUrl)
                429 -> throw AIQuotaExceededException(client.baseUrl)
                else -> throw e
            }
        }
    }

}

private const val SKILL_KEY = "skill-ai-recommend"
