package com.openclaw.ghostcrab.di

import org.koin.dsl.module

// ViewModels are registered per-phase as they are implemented.
val uiModule = module {
    // Phase 2: ConnectionPickerViewModel, ManualEntryViewModel
    // Phase 3: ScanViewModel
    // Phase 4: DashboardViewModel
    // Phase 5: OnboardingViewModel
    // Phase 6: ConfigEditorViewModel
    // Phase 7: ModelManagerViewModel
    // Phase 8: AIRecommendationViewModel
    // Phase 9: SettingsViewModel
}
