package com.openclaw.ghostcrab.di

import com.openclaw.ghostcrab.domain.repository.AIRecommendationService
import org.koin.dsl.module

val domainModule = module {
    // Phase 8: replace with AIRecommendationServiceImpl
    single<AIRecommendationService> { TODO("Phase 8") }
}
