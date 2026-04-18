package com.openclaw.ghostcrab.di

import com.openclaw.ghostcrab.data.api.OpenClawApiClient
import com.openclaw.ghostcrab.data.api.dto.HealthResponse
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
import com.openclaw.ghostcrab.domain.repository.InstalledSkillRepository
import com.openclaw.ghostcrab.domain.repository.ScopeProbe
import com.openclaw.ghostcrab.domain.repository.SettingsRepository
import com.openclaw.ghostcrab.data.impl.InstalledSkillRepositoryImpl
import com.openclaw.ghostcrab.data.impl.ScopeProbeImpl
import com.openclaw.ghostcrab.data.ws.openGatewayWs
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val dataModule = module {
    // Shared WebSocket-capable HttpClient — minimal, WS + JSON only.
    single<HttpClient> {
        HttpClient(OkHttp) {
            install(WebSockets)
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }

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

    if (com.openclaw.ghostcrab.BuildConfig.SKILLS_INSTALL_ENABLED) {
        single<InstalledSkillRepository> {
            InstalledSkillRepositoryImpl(
                wsFactory = {
                    val mgr = get<GatewayConnectionManagerImpl>()
                    val profile = (mgr.connectionState.value as? GatewayConnection.Connected)
                        ?: error("Cannot install skills while disconnected")
                    openGatewayWs(
                        httpClient = get(),
                        baseUrl = profile.url,
                        tokenProvider = { profile.tokenOrNull },
                    )
                }
            )
        }
        single<ScopeProbe> {
            ScopeProbeImpl(
                wsFactory = {
                    val mgr = get<GatewayConnectionManagerImpl>()
                    val profile = (mgr.connectionState.value as? GatewayConnection.Connected)
                        ?: error("Cannot probe scopes while disconnected")
                    openGatewayWs(
                        httpClient = get(),
                        baseUrl = profile.url,
                        tokenProvider = { profile.tokenOrNull },
                    )
                }
            )
        }
    }
}

/** Unauthenticated `/health` probe — creates and closes a client per call. */
internal suspend fun gatewayHealthCheck(url: String): HealthResponse {
    val client = OpenClawApiClient.unauthenticated(url)
    return try { client.health() } finally { client.close() }
}
