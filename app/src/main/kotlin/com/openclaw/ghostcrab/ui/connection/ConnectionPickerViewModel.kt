package com.openclaw.ghostcrab.ui.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openclaw.ghostcrab.domain.model.ConnectionProfile
import com.openclaw.ghostcrab.domain.repository.ConnectionProfileRepository
import com.openclaw.ghostcrab.domain.repository.GatewayConnectionManager
import com.openclaw.ghostcrab.domain.repository.OnboardingRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for the connection picker screen.
 *
 * On first launch (no saved profiles + onboarding not completed) emits a
 * [navigateToOnboarding] event so the host screen can route to the walkthrough.
 *
 * @param profileRepository Provides saved connection profiles.
 * @param connectionManager Manages the active gateway connection.
 * @param onboardingRepository Checks / marks onboarding completion state.
 */
public class ConnectionPickerViewModel(
    private val profileRepository: ConnectionProfileRepository,
    private val connectionManager: GatewayConnectionManager,
    private val onboardingRepository: OnboardingRepository,
) : ViewModel() {

    public val profiles: StateFlow<List<ConnectionProfile>> = profileRepository
        .getProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _showOnboarding = MutableStateFlow(false)

    /**
     * True when the first-launch condition is met (no profiles + onboarding incomplete).
     * Callers must invoke [onOnboardingNavigated] after consuming the event to reset the flag.
     */
    public val showOnboarding: StateFlow<Boolean> = _showOnboarding.asStateFlow()

    init {
        viewModelScope.launch {
            val isCompleted = onboardingRepository.isCompleted()
            if (!isCompleted) {
                // Wait for the first real emission from the profiles StateFlow.
                profiles.first()
                if (profiles.value.isEmpty()) {
                    _showOnboarding.value = true
                }
            }
        }
    }

    /** Call after navigating to onboarding so the flag does not re-trigger on recomposition. */
    public fun onOnboardingNavigated() {
        _showOnboarding.value = false
    }

    /** Deletes the saved profile with [profileId]. */
    public fun delete(profileId: String) {
        viewModelScope.launch {
            profileRepository.deleteProfile(profileId)
        }
    }

    /**
     * Attempts to connect to [profile].
     *
     * @param profile The profile to connect to.
     * @param onResult Invoked on the main thread with the connection result.
     */
    public fun connect(profile: ConnectionProfile, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            val result = runCatching {
                val token = if (profile.hasToken) profileRepository.getToken(profile.id) else null
                connectionManager.connect(profile.url, token)
            }
            onResult(result)
        }
    }
}
