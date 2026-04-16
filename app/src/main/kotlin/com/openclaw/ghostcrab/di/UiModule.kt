package com.openclaw.ghostcrab.di

import com.openclaw.ghostcrab.data.api.OpenClawApiClient
import com.openclaw.ghostcrab.ui.airecommend.AIRecommendationViewModel
import com.openclaw.ghostcrab.ui.config.ConfigEditorViewModel
import com.openclaw.ghostcrab.ui.connection.ConnectionPickerViewModel
import com.openclaw.ghostcrab.ui.connection.ManualEntryViewModel
import com.openclaw.ghostcrab.ui.connection.ScanViewModel
import com.openclaw.ghostcrab.ui.dashboard.DashboardViewModel
import com.openclaw.ghostcrab.ui.model.ModelManagerViewModel
import com.openclaw.ghostcrab.ui.onboarding.OnboardingViewModel
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val uiModule = module {
    viewModel { ConnectionPickerViewModel(get(), get(), get()) }
    viewModel { ManualEntryViewModel(get(), get()) }
    viewModel { ScanViewModel(get(), get(), get()) }
    viewModel {
        DashboardViewModel(
            connectionManager = get(),
            modelRepository = get(),
            healthChecker = { url ->
                val client = OpenClawApiClient.unauthenticated(url)
                try { client.health() } finally { client.close() }
            },
        )
    }
    viewModel { OnboardingViewModel(get()) }
    viewModel { ConfigEditorViewModel(get(), get()) }
    viewModel { ModelManagerViewModel(get(), get()) }
    viewModel { AIRecommendationViewModel(get(), get(), get(), get()) }
    // Phase 9: SettingsViewModel
}
