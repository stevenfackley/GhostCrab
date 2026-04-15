package com.openclaw.ghostcrab.ui.connection

import android.util.Patterns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openclaw.ghostcrab.domain.model.ConnectionProfile
import com.openclaw.ghostcrab.domain.repository.ConnectionProfileRepository
import com.openclaw.ghostcrab.domain.repository.GatewayConnectionManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class ManualEntryFormState(
    val url: String = "",
    val token: String = "",
    val tokenVisible: Boolean = false,
    val urlError: String? = null,
)

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
) : ViewModel() {

    private val _form = MutableStateFlow(ManualEntryFormState())
    val form: StateFlow<ManualEntryFormState> = _form.asStateFlow()

    private val _uiState = MutableStateFlow<ManualEntryUiState>(ManualEntryUiState.Idle)
    val uiState: StateFlow<ManualEntryUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ManualEntryEvent>()
    val events: SharedFlow<ManualEntryEvent> = _events.asSharedFlow()

    fun onUrlChange(value: String) {
        _form.update { it.copy(url = value, urlError = null) }
    }

    /** Pre-populates the URL field; called once by the screen when launched from LAN scan. */
    fun setPrefillUrl(url: String) {
        _form.update { it.copy(url = url, urlError = null) }
    }

    fun onTokenChange(value: String) {
        _form.update { it.copy(token = value) }
    }

    fun toggleTokenVisibility() {
        _form.update { it.copy(tokenVisible = !it.tokenVisible) }
    }

    fun connect() {
        val state = _form.value
        val urlError = validateUrl(state.url)
        if (urlError != null) {
            _form.update { it.copy(urlError = urlError) }
            return
        }

        viewModelScope.launch {
            _uiState.value = ManualEntryUiState.Connecting
            val token = state.token.trim().takeIf { it.isNotEmpty() }
            runCatching {
                connectionManager.connect(state.url.trim(), token)
            }.onSuccess {
                val connected = connectionManager.connectionState.value
                val profile = ConnectionProfile(
                    id = UUID.randomUUID().toString(),
                    displayName = (connected as? com.openclaw.ghostcrab.domain.model.GatewayConnection.Connected)
                        ?.displayName ?: state.url.trim(),
                    url = state.url.trim(),
                    lastConnectedAt = System.currentTimeMillis(),
                    hasToken = token != null,
                )
                profileRepository.saveProfile(profile, token)
                _uiState.value = ManualEntryUiState.Idle
                _events.emit(ManualEntryEvent.NavigateToDashboard)
            }.onFailure { e ->
                _uiState.value = ManualEntryUiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    private fun validateUrl(url: String): String? {
        if (url.isBlank()) return "URL is required"
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return "URL must start with http:// or https://"
        }
        if (!Patterns.WEB_URL.matcher(trimmed).matches()) return "Invalid URL format"
        val port = runCatching {
            java.net.URI(trimmed).port
        }.getOrDefault(-1)
        if (port != -1 && (port < 1 || port > 65535)) return "Port must be between 1 and 65535"
        return null
    }
}
