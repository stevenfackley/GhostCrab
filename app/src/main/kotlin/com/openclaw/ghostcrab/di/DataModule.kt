package com.openclaw.ghostcrab.di

import com.openclaw.ghostcrab.domain.repository.ConnectionProfileRepository
import org.koin.dsl.module

val dataModule = module {
    // Phase 2: replace with ConnectionProfileRepositoryImpl
    single<ConnectionProfileRepository> { TODO("Phase 2") }
}
