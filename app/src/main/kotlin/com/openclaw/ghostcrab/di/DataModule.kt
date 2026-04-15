package com.openclaw.ghostcrab.di

import com.openclaw.ghostcrab.data.discovery.NsdDiscoveryServiceImpl
import com.openclaw.ghostcrab.data.impl.ConnectionProfileRepositoryImpl
import com.openclaw.ghostcrab.data.impl.GatewayConnectionManagerImpl
import com.openclaw.ghostcrab.data.storage.ConnectionProfileStore
import com.openclaw.ghostcrab.domain.repository.ConnectionProfileRepository
import com.openclaw.ghostcrab.domain.repository.DiscoveryService
import com.openclaw.ghostcrab.domain.repository.GatewayConnectionManager
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val dataModule = module {
    single { ConnectionProfileStore(androidContext()) }
    single<ConnectionProfileRepository> { ConnectionProfileRepositoryImpl(get()) }
    single<GatewayConnectionManager> { GatewayConnectionManagerImpl() }
    single<DiscoveryService> { NsdDiscoveryServiceImpl(androidContext()) }
}
