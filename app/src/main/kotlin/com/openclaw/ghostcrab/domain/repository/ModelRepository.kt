package com.openclaw.ghostcrab.domain.repository

import com.openclaw.ghostcrab.domain.exception.GatewayApiException
import com.openclaw.ghostcrab.domain.model.ModelInfo

/**
 * Queries and manages language models registered on the gateway.
 *
 * **Contract frozen at v1.0.**
 */
interface ModelRepository {

    /**
     * Fetches the list of all models known to the gateway.
     *
     * @return List of [ModelInfo], where exactly one entry has [ModelInfo.isActive] = `true`
     *   (or an empty list if no models are configured).
     * @throws GatewayApiException on gateway errors.
     */
    suspend fun getModels(): List<ModelInfo>

    /**
     * Sets the active model on the gateway.
     *
     * After this call, a subsequent [getModels] will reflect the change.
     * Confirmation dialog is the caller's responsibility.
     *
     * @param modelId The [ModelInfo.id] of the model to activate.
     * @throws GatewayApiException if the model ID is unknown or the swap fails.
     */
    suspend fun setActiveModel(modelId: String)
}
