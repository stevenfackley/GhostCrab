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
import com.openclaw.ghostcrab.domain.repository.GatewayHealthChecker
import com.openclaw.ghostcrab.domain.repository.ModelRepository
import com.openclaw.ghostcrab.domain.repository.OnboardingRepository
import com.openclaw.ghostcrab.domain.repository.InstalledSkillRepository
import com.openclaw.ghostcrab.domain.repository.ScopeProbe
import com.openclaw.ghostcrab.domain.repository.SettingsRepository
import com.openclaw.ghostcrab.data.impl.InstalledSkillRepositoryImpl
import com.openclaw.ghostcrab.data.impl.ScopeProbeImpl
import com.openclaw.ghostcrab.data.ws.GatewayWsClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.scope.Scope
import org.koin.dsl.module

val dataModule = module {
    single { ConnectionProfileStore(androidContext()) }
    single<ConnectionProfileRepository> { ConnectionProfileRepositoryImpl(get()) }
    // Register the concrete type first so dependent impls can get<GatewayConnectionManagerImpl>()
    single { GatewayConnectionManagerImpl(settingsRepository = get()) }
    single<GatewayConnectionManager> { get<GatewayConnectionManagerImpl>() }
    single<GatewayHealthChecker> { get<GatewayConnectionManagerImpl>() }
    single<ConfigRepository> { ConfigRepositoryImpl(get<GatewayConnectionManagerImpl>()) }
    single<ModelRepository> { ModelRepositoryImpl(get<GatewayConnectionManagerImpl>()) }
    single<AIRecommendationService> { AIRecommendationServiceImpl(get<GatewayConnectionManagerImpl>()) }
    single<DiscoveryService> { NsdDiscoveryServiceImpl(androidContext()) }
    single<OnboardingRepository> { OnboardingRepositoryImpl(androidContext()) }
    single<SettingsRepository> { SettingsRepositoryImpl(androidContext()) }

    if (com.openclaw.ghostcrab.BuildConfig.SKILLS_INSTALL_ENABLED) {
        single<InstalledSkillRepository> { InstalledSkillRepositoryImpl(wsFactory = liveWsFactory()) }
        single<ScopeProbe> { ScopeProbeImpl(wsFactory = liveWsFactory()) }
    }
}

/**
 * Opens a fresh JSON-RPC WebSocket on the live session client. Shared by every WS-backed
 * repository; throws `GatewayNotConnectedException` while disconnected.
 */
private fun Scope.liveWsFactory(): suspend () -> GatewayWsClient {
    val manager = get<GatewayConnectionManagerImpl>()
    return { manager.requireClient().openWebSocket() }
}
