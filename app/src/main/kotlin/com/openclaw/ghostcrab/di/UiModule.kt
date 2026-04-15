package com.openclaw.ghostcrab.di

import com.openclaw.ghostcrab.ui.connection.ConnectionPickerViewModel
import com.openclaw.ghostcrab.ui.connection.ManualEntryViewModel
import com.openclaw.ghostcrab.ui.connection.ScanViewModel
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val uiModule = module {
    viewModel { ConnectionPickerViewModel(get(), get()) }
    viewModel { ManualEntryViewModel(get(), get()) }
    viewModel { ScanViewModel(get(), get(), get()) }
    // Phase 4: DashboardViewModel
    // Phase 5: OnboardingViewModel
    // Phase 6: ConfigEditorViewModel
    // Phase 7: ModelManagerViewModel
    // Phase 8: AIRecommendationViewModel
    // Phase 9: SettingsViewModel
}

