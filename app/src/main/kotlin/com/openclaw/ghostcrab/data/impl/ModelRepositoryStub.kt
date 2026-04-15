package com.openclaw.ghostcrab.data.impl

import com.openclaw.ghostcrab.domain.model.ModelInfo
import com.openclaw.ghostcrab.domain.repository.ModelRepository

/**
 * Stub [ModelRepository] that returns no models.
 *
 * Replaced in Phase 7 by a Ktor-backed implementation.
 */
class ModelRepositoryStub : ModelRepository {
    override suspend fun getModels(): List<ModelInfo> = emptyList()
    override suspend fun setActiveModel(modelId: String) = Unit
}
