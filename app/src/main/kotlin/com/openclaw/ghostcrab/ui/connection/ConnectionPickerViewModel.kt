package com.openclaw.ghostcrab.ui.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openclaw.ghostcrab.domain.exception.GatewayPairingRequiredException
import com.openclaw.ghostcrab.domain.model.ConnectionProfile
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import com.openclaw.ghostcrab.domain.repository.ConnectionProfileRepository
import com.openclaw.ghostcrab.domain.repository.GatewayConnectionManager
import com.openclaw.ghostcrab.domain.repository.OnboardingRepository
import com.openclaw.ghostcrab.ui.toUserMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Transient picker state.
 *
 * @param connectingProfileId Profile whose connect is in flight, or `null`.
 * @param errorMessage Last connect failure, formatted for display; cleared by
 *   [ConnectionPickerViewModel.clearError].
 * @param pairingPrompt Set while the gateway waits for this device to be approved (ADR-0007).
 */
data class ConnectionPickerUiState(
    val connectingProfileId: String? = null,
    val errorMessage: String? = null,
    val pairingPrompt: PairingPrompt? = null,
)

/** One-shot navigation events for [ConnectionPickerScreen]. */
sealed interface ConnectionPickerEvent {
    /** Connected — show the dashboard. */
    data object NavigateToDashboard : ConnectionPickerEvent
}

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
                // Read directly from the repository to avoid racing against stateIn's
                // initial-value emission (which is always emptyList() regardless of upstream).
                if (profileRepository.getProfiles().first().isEmpty()) {
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

    private val _uiState = MutableStateFlow(ConnectionPickerUiState())

    /** Connect progress and errors; survives rotation. */
    public val uiState: StateFlow<ConnectionPickerUiState> = _uiState.asStateFlow()

    private val _events = Channel<ConnectionPickerEvent>(Channel.BUFFERED)

    /** One-shot navigation events, buffered across rotation. */
    public val events: Flow<ConnectionPickerEvent> = _events.receiveAsFlow()

    /**
     * The running connect and its approval wait; the approval card's Check now / Cancel go here, and the screen
     * cancels it before it navigates away.
     */
    public val pairing: PairingWait =
        PairingWait(viewModelScope, connectionManager) { _uiState.value = ConnectionPickerUiState() }

    init {
        resumePendingApproval()
    }

    /**
     * Connects to [profile] with its stored token. Ignored while another connect is in flight.
     * Sends [ConnectionPickerEvent.NavigateToDashboard] on success; sets
     * [ConnectionPickerUiState.errorMessage] on failure. On `PAIRING_REQUIRED` sets
     * [ConnectionPickerUiState.pairingPrompt] and retries until the device is approved, the pending request
     * expires (an explicit error) or [PairingWait.cancel].
     *
     * @param profile The profile to connect to.
     */
    public fun connect(profile: ConnectionProfile) {
        if (_uiState.value.connectingProfileId != null || pairing.isRunning) return
        startConnect(profile, first = null)
    }

    /**
     * The dashboard path: a silent reconnect the gateway refused with `PAIRING_REQUIRED` ends in that Error, the
     * dashboard reads it as disconnected and returns here. When the Error's URL is a saved profile, the approval
     * wait for it starts at once, from that refusal, instead of the request being dropped.
     */
    private fun resumePendingApproval() {
        val error = connectionManager.connectionState.value as? GatewayConnection.Error ?: return
        val refusal = error.cause as? GatewayPairingRequiredException ?: return
        viewModelScope.launch {
            val profile = profileRepository.getProfiles().first().firstOrNull { it.url == error.url }
            if (profile != null && _uiState.value.connectingProfileId == null && !pairing.isRunning) {
                startConnect(profile, first = refusal)
            }
        }
    }

    private fun startConnect(profile: ConnectionProfile, first: GatewayPairingRequiredException?) {
        _uiState.value = ConnectionPickerUiState(connectingProfileId = profile.id)
        pairing.start(
            url = profile.url,
            onPrompt = { prompt -> _uiState.update { it.copy(pairingPrompt = prompt) } },
            connect = {
                val token = if (profile.hasToken) profileRepository.getToken(profile.id) else null
                connectionManager.connect(profile.url, token)
            },
            first = first,
            onFinished = ::onConnectFinished,
        )
    }

    /** Navigates on success; otherwise shows the expired approval window or the failure. */
    private suspend fun onConnectFinished(result: Result<Boolean>) {
        result.onSuccess { approved ->
            if (approved) {
                _uiState.value = ConnectionPickerUiState()
                _events.send(ConnectionPickerEvent.NavigateToDashboard)
            } else {
                _uiState.value = ConnectionPickerUiState(errorMessage = PAIRING_TIMEOUT_MESSAGE)
            }
        }.onFailure { e ->
            _uiState.value = ConnectionPickerUiState(errorMessage = e.toUserMessage())
        }
    }

    /** Clears [ConnectionPickerUiState.errorMessage] after the snackbar was shown. */
    public fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
