package com.openclaw.ghostcrab.data.impl

import com.openclaw.ghostcrab.domain.exception.AIServiceUnavailableException
import com.openclaw.ghostcrab.domain.exception.GatewayNotConnectedException
import com.openclaw.ghostcrab.domain.model.AIRecommendation
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import com.openclaw.ghostcrab.domain.model.RecommendationContext
import com.openclaw.ghostcrab.domain.repository.AIRecommendationService

/**
 * OpenClaw gateways have no recommendation API: `POST /api/ai/recommend` is 404 and no WS method exists
 * (QA §8.11, ADR-0007). Until a `chat.send`-based design has its own ADR, the feature is unsupported: never
 * available, and a request fails with [AIServiceUnavailableException] without any network call, so no config
 * (secrets included) ever leaves the device. The UI hides the feature behind `BuildConfig.AI_PRO_ENABLED`.
 *
 * @param connectionManager Supplies the gateway URL for the error.
 */
class AIRecommendationServiceImpl(
    private val connectionManager: GatewayConnectionManagerImpl,
) : AIRecommendationService {

    /** @return Always `false`: no OpenClaw gateway offers recommendations (ADR-0007). */
    override suspend fun isAvailable(): Boolean = false

    /**
     * @param query Ignored.
     * @param context Ignored; never sent anywhere.
     * @return Never returns.
     * @throws AIServiceUnavailableException always, naming the connected gateway (not retryable).
     * @throws GatewayNotConnectedException if no session is active.
     */
    override suspend fun getRecommendation(query: String, context: RecommendationContext): AIRecommendation {
        val connected = connectionManager.connectionState.value as? GatewayConnection.Connected
            ?: throw GatewayNotConnectedException()
        throw AIServiceUnavailableException(connected.url)
    }
}
