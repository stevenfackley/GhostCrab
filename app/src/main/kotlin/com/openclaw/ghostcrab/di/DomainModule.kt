package com.openclaw.ghostcrab.di

import org.koin.dsl.module

/**
 * Domain-layer Koin module.
 *
 * All domain interface bindings are in [dataModule] alongside their implementations so Koin
 * can resolve both the concrete type (needed by other impls) and the interface alias in one place.
 */
val domainModule = module {
    // Domain bindings live in dataModule — see DataModule.kt
}
