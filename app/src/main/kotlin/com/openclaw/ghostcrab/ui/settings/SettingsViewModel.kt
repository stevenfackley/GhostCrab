package com.openclaw.ghostcrab.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openclaw.ghostcrab.domain.model.ConnectionProfile
import com.openclaw.ghostcrab.domain.repository.ConnectionProfileRepository
import com.openclaw.ghostcrab.domain.repository.OnboardingRepository
import com.openclaw.ghostcrab.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for [SettingsScreen].
 *
 * Observes profiles and the cleartext preference from their respective repositories
 * and merges them into a single [SettingsUiState.Ready] state.
 *
 * @param profileRepository Source for saved gateway profiles.
 * @param settingsRepository Source for app-level preferences.
 * @param onboardingRepository Used to reset the walkthrough.
 */
class SettingsViewModel(
    private val profileRepository: ConnectionProfileRepository,
    private val settingsRepository: SettingsRepository,
    private val onboardingRepository: OnboardingRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<SettingsUiState>(SettingsUiState.Loading)

    /** Observable UI state. */
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                profileRepository.getProfiles(),
                settingsRepository.allowCleartextPublicIPs,
            ) { profiles, allowCleartext ->
                // Preserve transient overlay state (dialogs, errors) across data refreshes
                val overlay = _state.value as? SettingsUiState.Ready
                SettingsUiState.Ready(
                    profiles = profiles,
                    allowCleartextPublicIPs = allowCleartext,
                    pendingDeleteProfileId = overlay?.pendingDeleteProfileId,
                    showClearAllConfirmation = overlay?.showClearAllConfirmation ?: false,
                    editingProfile = overlay?.editingProfile,
                    onboardingResetSuccess = overlay?.onboardingResetSuccess ?: false,
                    errorMessage = overlay?.errorMessage,
                )
            }.collect { _state.value = it }
        }
    }

    // ── Connections ───────────────────────────────────────────────────────────

    /**
     * Toggles the "allow cleartext to public IPs" preference.
     *
     * @param enabled New value.
     */
    fun setAllowCleartextPublicIPs(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAllowCleartextPublicIPs(enabled)
        }
    }

    /**
     * Opens the delete-confirmation dialog for [profileId].
     */
    fun requestDeleteProfile(profileId: String) {
        withReady { it.copy(pendingDeleteProfileId = profileId) }
    }

    /**
     * Cancels the pending profile deletion dialog.
     */
    fun cancelDeleteProfile() {
        withReady { it.copy(pendingDeleteProfileId = null) }
    }

    /**
     * Confirms and executes deletion of the profile currently pending deletion.
     *
     * No-op if no profile is pending. Sets [SettingsUiState.Ready.errorMessage] on failure.
     */
    fun confirmDeleteProfile() {
        val profileId = (_state.value as? SettingsUiState.Ready)?.pendingDeleteProfileId ?: return
        withReady { it.copy(pendingDeleteProfileId = null) }
        viewModelScope.launch {
            runCatching { profileRepository.deleteProfile(profileId) }
                .onFailure { e -> withReady { s -> s.copy(errorMessage = e.message) } }
        }
    }

    /**
     * Opens the edit sheet for [profile].
     */
    fun startEditProfile(profile: ConnectionProfile) {
        withReady { it.copy(editingProfile = profile) }
    }

    /**
     * Saves an edited [displayName] and optional [newToken] for the profile being edited.
     *
     * Closes the edit sheet. Sets [SettingsUiState.Ready.errorMessage] on failure.
     *
     * @param displayName New display name. Must be non-blank.
     * @param newToken New bearer token, or `null` to leave the stored token unchanged.
     *   Pass an empty string to clear the token.
     */
    fun saveProfileEdit(displayName: String, newToken: String?) {
        val current = (_state.value as? SettingsUiState.Ready)?.editingProfile ?: return
        withReady { it.copy(editingProfile = null) }
        viewModelScope.launch {
            runCatching {
                val updated = current.copy(displayName = displayName.trim())
                val tokenToSave = when {
                    newToken == null -> profileRepository.getToken(current.id) // unchanged
                    newToken.isBlank() -> null  // clear
                    else -> newToken
                }
                profileRepository.saveProfile(updated, tokenToSave)
            }.onFailure { e -> withReady { s -> s.copy(errorMessage = e.message) } }
        }
    }

    /**
     * Dismisses the edit sheet without saving.
     */
    fun cancelEditProfile() {
        withReady { it.copy(editingProfile = null) }
    }

    // ── Security ──────────────────────────────────────────────────────────────

    /**
     * Shows the "Clear all profiles" confirmation dialog.
     */
    fun requestClearAllProfiles() {
        withReady { it.copy(showClearAllConfirmation = true) }
    }

    /**
     * Dismisses the "Clear all profiles" confirmation dialog.
     */
    fun cancelClearAllProfiles() {
        withReady { it.copy(showClearAllConfirmation = false) }
    }

    /**
     * Deletes all saved profiles. Sets [SettingsUiState.Ready.errorMessage] on partial failure.
     */
    fun confirmClearAllProfiles() {
        val profiles = (_state.value as? SettingsUiState.Ready)?.profiles ?: return
        withReady { it.copy(showClearAllConfirmation = false) }
        viewModelScope.launch {
            profiles.forEach { profile ->
                runCatching { profileRepository.deleteProfile(profile.id) }
                    .onFailure { e -> withReady { s -> s.copy(errorMessage = e.message) } }
            }
        }
    }

    // ── Onboarding ────────────────────────────────────────────────────────────

    /**
     * Resets onboarding state so the walkthrough shows again on the next launch.
     *
     * Sets [SettingsUiState.Ready.onboardingResetSuccess] to `true` on success.
     */
    fun replayWalkthrough() {
        viewModelScope.launch {
            runCatching { onboardingRepository.reset() }
                .onSuccess { withReady { it.copy(onboardingResetSuccess = true) } }
                .onFailure { e -> withReady { s -> s.copy(errorMessage = e.message) } }
        }
    }

    /**
     * Clears the walkthrough-reset success flag after it has been shown.
     */
    fun clearOnboardingResetSuccess() {
        withReady { it.copy(onboardingResetSuccess = false) }
    }

    // ── Error ─────────────────────────────────────────────────────────────────

    /**
     * Clears the transient error message after it has been shown.
     */
    fun clearError() {
        withReady { it.copy(errorMessage = null) }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun withReady(block: (SettingsUiState.Ready) -> SettingsUiState) {
        _state.update { if (it is SettingsUiState.Ready) block(it) else it }
    }
}
