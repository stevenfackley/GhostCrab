package com.openclaw.ghostcrab.di

import com.openclaw.ghostcrab.BuildConfig
import com.openclaw.ghostcrab.data.discovery.NsdDiscoveryServiceImpl
import com.openclaw.ghostcrab.data.gateway.ClientInfo
import com.openclaw.ghostcrab.data.gateway.DeviceIdentityProvider
import com.openclaw.ghostcrab.data.gateway.DeviceTokenStore

import com.openclaw.ghostcrab.data.impl.AIRecommendationServiceImpl
import com.openclaw.ghostcrab.data.impl.ConfigRepositoryImpl
import com.openclaw.ghostcrab.data.impl.ConnectionProfileRepositoryImpl
import com.openclaw.ghostcrab.data.impl.GatewayConnectionManagerImpl
import com.openclaw.ghostcrab.data.impl.ModelRepositoryImpl
import com.openclaw.ghostcrab.data.impl.OnboardingRepositoryImpl
import com.openclaw.ghostcrab.data.impl.SettingsRepositoryImpl
import com.openclaw.ghostcrab.data.impl.appSettingsDataStore
import com.openclaw.ghostcrab.data.impl.onboardingDataStore
import com.openclaw.ghostcrab.data.storage.ConnectionProfileStore
import com.openclaw.ghostcrab.data.storage.DeviceIdentityStore
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import org.koin.dsl.module
import java.util.Locale

/** Koin qualifier for the blocking-IO dispatcher. Inject it; never hard-code `Dispatchers.IO` in data classes. */
val IoDispatcher = named("io")

val dataModule = module {
    single<CoroutineDispatcher>(IoDispatcher) { Dispatchers.IO }
    single { ConnectionProfileStore(androidContext(), ioDispatcher = get(IoDispatcher)) }
    single<ConnectionProfileRepository> { ConnectionProfileRepositoryImpl(get()) }
    // Register the concrete type first so dependent impls can get<GatewayConnectionManagerImpl>()
    single<DeviceTokenStore> { get<ConnectionProfileStore>() }
    // One instance per process: its mutex keeps two first-use calls from generating two identities.
    single<DeviceIdentityProvider> { DeviceIdentityStore(androidContext(), ioDispatcher = get(IoDispatcher)) }
    single { androidClientInfo() }
    single {
        GatewayConnectionManagerImpl(
            identityProvider = get(),
            deviceTokens = get(),
            clientInfo = get(),
            settingsRepository = get(),
            ioDispatcher = get(IoDispatcher),
        )
    }
    single<GatewayConnectionManager> { get<GatewayConnectionManagerImpl>() }
    single<GatewayHealthChecker> { get<GatewayConnectionManagerImpl>() }
    single<ConfigRepository> { ConfigRepositoryImpl(get<GatewayConnectionManagerImpl>()) }
    single<ModelRepository> { ModelRepositoryImpl(get<GatewayConnectionManagerImpl>()) }
    single<AIRecommendationService> { AIRecommendationServiceImpl(get<GatewayConnectionManagerImpl>()) }
    single<DiscoveryService> { NsdDiscoveryServiceImpl(androidContext()) }
    single<OnboardingRepository> { OnboardingRepositoryImpl(androidContext().onboardingDataStore) }
    single<SettingsRepository> { SettingsRepositoryImpl(androidContext().appSettingsDataStore) }

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

/** `connect.params.client` for this build (ADR-0007 decision 4: closed upstream id, honest version and name). */
private fun androidClientInfo() = ClientInfo(
    id = "openclaw-android",
    version = BuildConfig.VERSION_NAME,
    platform = "android",
    displayName = "GhostCrab (Android)",
    locale = Locale.getDefault().toLanguageTag(),
)
