package com.openclaw.ghostcrab.di

import org.koin.dsl.module

/**
 * Top-level Koin module that aggregates all sub-modules.
 * Included in [com.openclaw.ghostcrab.GhostCrabApp].
 */
val appModule = module {
    includes(dataModule, domainModule, uiModule)
}
