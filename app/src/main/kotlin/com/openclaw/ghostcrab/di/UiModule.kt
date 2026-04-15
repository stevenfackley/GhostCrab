package com.openclaw.ghostcrab.di

import com.openclaw.ghostcrab.ui.connection.ConnectionPickerViewModel
import com.openclaw.ghostcrab.ui.connection.ManualEntryViewModel
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val uiModule = module {
    viewModel { ConnectionPickerViewModel(get(), get()) }
    viewModel { ManualEntryViewModel(get(), get()) }
    // Phase 3: ScanViewModel
    // Phase 4: DashboardViewModel
    // Phase 5: OnboardingViewModel
    // Phase 6: ConfigEditorViewModel
    // Phase 7: ModelManagerViewModel
    // Phase 8: AIRecommendationViewModel
    // Phase 9: SettingsViewModel
}
