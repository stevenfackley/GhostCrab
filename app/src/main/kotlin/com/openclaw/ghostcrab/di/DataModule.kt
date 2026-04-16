package com.openclaw.ghostcrab.di

import com.openclaw.ghostcrab.data.discovery.NsdDiscoveryServiceImpl
import com.openclaw.ghostcrab.data.impl.AIRecommendationServiceImpl
import com.openclaw.ghostcrab.data.impl.ConfigRepositoryImpl
import com.openclaw.ghostcrab.data.impl.ConnectionProfileRepositoryImpl
import com.openclaw.ghostcrab.data.impl.GatewayConnectionManagerImpl
import com.openclaw.ghostcrab.data.impl.ModelRepositoryImpl
import com.openclaw.ghostcrab.data.impl.OnboardingRepositoryImpl
import com.openclaw.ghostcrab.data.impl.SettingsRepositoryImpl
import com.openclaw.ghostcrab.data.storage.ConnectionProfileStore
import com.openclaw.ghostcrab.domain.repository.AIRecommendationService
import com.openclaw.ghostcrab.domain.repository.ConfigRepository
import com.openclaw.ghostcrab.domain.repository.ConnectionProfileRepository
import com.openclaw.ghostcrab.domain.repository.DiscoveryService
import com.openclaw.ghostcrab.domain.repository.GatewayConnectionManager
import com.openclaw.ghostcrab.domain.repository.ModelRepository
import com.openclaw.ghostcrab.domain.repository.OnboardingRepository
import com.openclaw.ghostcrab.domain.repository.SettingsRepository
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val dataModule = module {
    single { ConnectionProfileStore(androidContext()) }
    single<ConnectionProfileRepository> { ConnectionProfileRepositoryImpl(get()) }
    // Register the concrete type first so dependent impls can get<GatewayConnectionManagerImpl>()
    single { GatewayConnectionManagerImpl(settingsRepository = get()) }
    single<GatewayConnectionManager> { get<GatewayConnectionManagerImpl>() }
    single<ConfigRepository> { ConfigRepositoryImpl(get<GatewayConnectionManagerImpl>()) }
    single<ModelRepository> { ModelRepositoryImpl(get<GatewayConnectionManagerImpl>()) }
    single<AIRecommendationService> { AIRecommendationServiceImpl(get<GatewayConnectionManagerImpl>()) }
    single<DiscoveryService> { NsdDiscoveryServiceImpl(androidContext()) }
    single<OnboardingRepository> { OnboardingRepositoryImpl(androidContext()) }
    single<SettingsRepository> { SettingsRepositoryImpl(androidContext()) }
}
