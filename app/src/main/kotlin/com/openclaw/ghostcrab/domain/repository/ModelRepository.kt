package com.openclaw.ghostcrab.domain.repository

import com.openclaw.ghostcrab.domain.exception.GatewayApiException
import com.openclaw.ghostcrab.domain.model.ModelInfo

/**
 * Queries and manages language models registered on the gateway (ADR-0007: `models.list`, and the
 * `agents.defaults.model.primary` config key for the active model).
 *
 * **Contract frozen at v1.0.**
 */
interface ModelRepository {

    /**
     * Fetches the list of all models known to the gateway.
     *
     * @return List of [ModelInfo] (empty if no models are configured). At most one entry has
     *   [ModelInfo.isActive] = `true`: none when no primary model is configured, when it names a model the
     *   catalogue lacks, or when the session may not read the config (no `operator.admin`).
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayException on gateway errors.
     */
    suspend fun getModels(): List<ModelInfo>

    /**
     * Sets the active model on the gateway.
     *
     * After this call, a subsequent [getModels] will reflect the change.
     * Confirmation dialog is the caller's responsibility.
     *
     * @param modelId The [ModelInfo.id] of the model to activate (`provider/model`).
     * @throws GatewayApiException 412 if the gateway config changed while the swap was being written (retry), 422
     *   if the gateway rejects the model.
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayException on any other gateway failure.
     */
    suspend fun setActiveModel(modelId: String)
}
