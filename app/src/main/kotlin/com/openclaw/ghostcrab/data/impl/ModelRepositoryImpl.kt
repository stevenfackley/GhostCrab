package com.openclaw.ghostcrab.data.impl

import com.openclaw.ghostcrab.data.api.dto.ModelDto
import com.openclaw.ghostcrab.domain.model.ModelInfo
import com.openclaw.ghostcrab.domain.repository.ModelRepository

/**
 * Ktor-backed [ModelRepository] that talks to the connected gateway.
 *
 * Requires an active connection — [GatewayConnectionManagerImpl.requireClient] throws
 * [IllegalStateException] if called while disconnected, which propagates to callers.
 *
 * @param connectionManager Concrete manager used to obtain the active [OpenClawApiClient].
 */
public class ModelRepositoryImpl(
    private val connectionManager: GatewayConnectionManagerImpl,
) : ModelRepository {

    /**
     * Fetches the current model list from `GET /api/models/status`.
     *
     * @return List of [ModelInfo] mapped 1:1 from the gateway DTOs.
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayException on network/API errors.
     * @throws IllegalStateException if no gateway connection is active.
     */
    override suspend fun getModels(): List<ModelInfo> =
        connectionManager.requireClient().getModels().map { it.toModelInfo() }

    /**
     * Sets the active model via `POST /api/models/active`.
     *
     * @param modelId The `id` of the model to activate.
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayException on network/API errors.
     * @throws IllegalStateException if no gateway connection is active.
     */
    override suspend fun setActiveModel(modelId: String): Unit =
        connectionManager.requireClient().setActiveModel(modelId)
}

// ── Mapping ───────────────────────────────────────────────────────────────────

private fun ModelDto.toModelInfo(): ModelInfo = ModelInfo(
    id = id,
    provider = provider,
    displayName = displayName,
    isActive = isActive,
    status = status,
    capabilities = capabilities,
)
