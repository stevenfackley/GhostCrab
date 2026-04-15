package com.openclaw.ghostcrab.di

import com.openclaw.ghostcrab.domain.repository.AIRecommendationService
import com.openclaw.ghostcrab.domain.repository.ConfigRepository
import com.openclaw.ghostcrab.domain.repository.DiscoveryService
import com.openclaw.ghostcrab.domain.repository.GatewayConnectionManager
import com.openclaw.ghostcrab.domain.repository.ModelRepository
import org.koin.dsl.module

val domainModule = module {
    // Phase 2: replace with GatewayConnectionManagerImpl
    single<GatewayConnectionManager> { TODO("Phase 2") }
    // Phase 3: replace with NsdDiscoveryServiceImpl
    single<DiscoveryService> { TODO("Phase 3") }
    // Phase 6: replace with ConfigRepositoryImpl
    single<ConfigRepository> { TODO("Phase 6") }
    // Phase 7: replace with ModelRepositoryImpl
    single<ModelRepository> { TODO("Phase 7") }
    // Phase 8: replace with AIRecommendationServiceImpl
    single<AIRecommendationService> { TODO("Phase 8") }
}
