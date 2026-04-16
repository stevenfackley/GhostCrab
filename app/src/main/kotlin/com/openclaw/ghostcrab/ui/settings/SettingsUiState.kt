package com.openclaw.ghostcrab.ui.settings

import com.openclaw.ghostcrab.domain.model.ConnectionProfile

/**
 * Exhaustive UI state for [SettingsScreen].
 */
sealed interface SettingsUiState {

    /** Preferences and profiles are still loading from DataStore. */
    data object Loading : SettingsUiState

    /**
     * All data loaded and ready.
     *
     * @param profiles All locally saved gateway profiles.
     * @param allowCleartextPublicIPs Current value of the cleartext-to-public-IPs preference.
     * @param pendingDeleteProfileId Profile ID awaiting delete confirmation. `null` if no dialog shown.
     * @param showClearAllConfirmation `true` while the "Clear all profiles" confirmation is displayed.
     * @param editingProfile Profile currently being edited in the edit sheet. `null` if none.
     * @param onboardingResetSuccess Transient flag — `true` after a successful walkthrough reset.
     *   Cleared by [SettingsViewModel.clearOnboardingResetSuccess].
     * @param errorMessage Transient error message from a failed operation. `null` when clear.
     *   Cleared by [SettingsViewModel.clearError].
     */
    data class Ready(
        val profiles: List<ConnectionProfile>,
        val allowCleartextPublicIPs: Boolean,
        val pendingDeleteProfileId: String? = null,
        val showClearAllConfirmation: Boolean = false,
        val editingProfile: ConnectionProfile? = null,
        val onboardingResetSuccess: Boolean = false,
        val errorMessage: String? = null,
    ) : SettingsUiState
}
