package com.openclaw.ghostcrab.di

import com.openclaw.ghostcrab.data.impl.ModelRepositoryStub
import com.openclaw.ghostcrab.domain.repository.AIRecommendationService
import com.openclaw.ghostcrab.domain.repository.ConfigRepository
import com.openclaw.ghostcrab.domain.repository.ModelRepository
import org.koin.dsl.module

val domainModule = module {
    // Phase 6: replace with ConfigRepositoryImpl
    single<ConfigRepository> { TODO("Phase 6") }
    // Phase 7: replace with ModelRepositoryImpl
    single<ModelRepository> { ModelRepositoryStub() }
    // Phase 8: replace with AIRecommendationServiceImpl
    single<AIRecommendationService> { TODO("Phase 8") }
}
