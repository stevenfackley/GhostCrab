package com.openclaw.ghostcrab.ui.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openclaw.ghostcrab.domain.model.ConnectionProfile
import com.openclaw.ghostcrab.domain.repository.ConnectionProfileRepository
import com.openclaw.ghostcrab.domain.repository.GatewayConnectionManager
import com.openclaw.ghostcrab.domain.repository.OnboardingRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URI
import java.util.UUID

const val DEFAULT_GATEWAY_PORT = "18789"

data class ManualEntryFormState(
    val useHttps: Boolean = false,
    val host: String = "",
    val port: String = DEFAULT_GATEWAY_PORT,
    val token: String = "",
    val tokenVisible: Boolean = false,
    val hostError: String? = null,
    val portError: String? = null,
) {
    val scheme: String get() = if (useHttps) "https" else "http"
    val assembledUrl: String get() = "$scheme://${host.trim()}:${port.trim()}"
}

sealed interface ManualEntryUiState {
    data object Idle : ManualEntryUiState
    data object Connecting : ManualEntryUiState
    data class Error(val message: String) : ManualEntryUiState
}

sealed interface ManualEntryEvent {
    data object NavigateToDashboard : ManualEntryEvent
}

class ManualEntryViewModel(
    private val connectionManager: GatewayConnectionManager,
    private val profileRepository: ConnectionProfileRepository,
    private val onboardingRepository: OnboardingRepository,
) : ViewModel() {

    private val _form = MutableStateFlow(ManualEntryFormState())
    val form: StateFlow<ManualEntryFormState> = _form.asStateFlow()

    private val _uiState = MutableStateFlow<ManualEntryUiState>(ManualEntryUiState.Idle)
    val uiState: StateFlow<ManualEntryUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ManualEntryEvent>()
    val events: SharedFlow<ManualEntryEvent> = _events.asSharedFlow()

    fun onHostChange(value: String) {
        _form.update { it.copy(host = value, hostError = null) }
    }

    fun onPortChange(value: String) {
        val digitsOnly = value.filter(Char::isDigit).take(5)
        _form.update { it.copy(port = digitsOnly, portError = null) }
    }

    fun onHttpsToggle(useHttps: Boolean) {
        _form.update { it.copy(useHttps = useHttps) }
    }

    /** Parses a full URL from LAN scan and populates scheme/host/port individually. */
    fun setPrefillUrl(url: String) {
        val uri = runCatching { URI(url.trim()) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        val host = uri?.host.orEmpty()
        val port = uri?.port?.takeIf { it > 0 }?.toString() ?: DEFAULT_GATEWAY_PORT
        _form.update {
            it.copy(
                useHttps = scheme == "https",
                host = host,
                port = port,
                hostError = null,
                portError = null,
            )
        }
    }

    fun onTokenChange(value: String) {
        _form.update { it.copy(token = value) }
    }

    fun toggleTokenVisibility() {
        _form.update { it.copy(tokenVisible = !it.tokenVisible) }
    }

    fun connect() {
        val state = _form.value
        val hostError = validateHost(state.host)
        val portError = validatePort(state.port)
        if (hostError != null || portError != null) {
            _form.update { it.copy(hostError = hostError, portError = portError) }
            return
        }

        val url = state.assembledUrl
        viewModelScope.launch {
            _uiState.value = ManualEntryUiState.Connecting
            val token = state.token.trim().takeIf { it.isNotEmpty() }
            runCatching {
                connectionManager.connect(url, token)
            }.onSuccess {
                val connected = connectionManager.connectionState.value
                val displayName =
                    (connected as? com.openclaw.ghostcrab.domain.model.GatewayConnection.Connected)
                        ?.displayName ?: url
                val existingId = profileRepository.getProfiles().first()
                    .firstOrNull { it.url == url }?.id
                val profile = ConnectionProfile(
                    id = existingId ?: UUID.randomUUID().toString(),
                    displayName = displayName,
                    url = url,
                    lastConnectedAt = System.currentTimeMillis(),
                    hasToken = token != null,
                )
                profileRepository.saveProfile(profile, token)
                onboardingRepository.markCompleted()
                _uiState.value = ManualEntryUiState.Idle
                _events.emit(ManualEntryEvent.NavigateToDashboard)
            }.onFailure { e ->
                _uiState.value = ManualEntryUiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    @Suppress("ReturnCount")
    private fun validateHost(host: String): String? {
        val trimmed = host.trim()
        if (trimmed.isEmpty()) return "Host is required"
        if (trimmed.contains("://")) return "Remove scheme — use the HTTPS toggle instead"
        if (trimmed.contains('/')) return "Host must not contain '/'"
        if (trimmed.contains(':')) return "Port goes in the Port field, not the Host field"
        if (trimmed.any { it.isWhitespace() }) return "Host must not contain whitespace"
        return null
    }

    @Suppress("ReturnCount")
    private fun validatePort(port: String): String? {
        val trimmed = port.trim()
        if (trimmed.isEmpty()) return "Port is required"
        val n = trimmed.toIntOrNull() ?: return "Port must be a number"
        if (n < 1 || n > 65535) return "Port must be between 1 and 65535"
        return null
    }
}
