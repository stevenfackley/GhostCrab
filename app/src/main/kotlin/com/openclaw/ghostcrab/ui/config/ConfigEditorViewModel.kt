package com.openclaw.ghostcrab.ui.config

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openclaw.ghostcrab.domain.exception.ConfigValidationException
import com.openclaw.ghostcrab.domain.exception.GatewayApiException
import com.openclaw.ghostcrab.domain.exception.GatewayException
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import com.openclaw.ghostcrab.domain.repository.ConfigRepository
import com.openclaw.ghostcrab.domain.repository.GatewayConnectionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/**
 * ViewModel for the Config Editor screen.
 *
 * Observes [GatewayConnectionManager.connectionState] to load config when connected and
 * transition to [ConfigEditorUiState.Disconnected] when the connection drops.
 *
 * All mutations are server-confirmed — there is no optimistic local state.
 *
 * @param connectionManager Provides connection lifecycle events.
 * @param configRepository Gateway config read/write access.
 */
class ConfigEditorViewModel(
    private val connectionManager: GatewayConnectionManager,
    private val configRepository: ConfigRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<ConfigEditorUiState>(ConfigEditorUiState.Loading)

    /** Current UI state. Collect in the composable. */
    val state: StateFlow<ConfigEditorUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            connectionManager.connectionState.collect { conn ->
                when (conn) {
                    is GatewayConnection.Connected -> loadConfig()
                    is GatewayConnection.Disconnected -> _state.value = ConfigEditorUiState.Disconnected
                    else -> Unit
                }
            }
        }
    }

    /**
     * Fetches (or re-fetches) the config from the gateway.
     *
     * Transitions to [ConfigEditorUiState.Loading] then either [ConfigEditorUiState.Ready]
     * or [ConfigEditorUiState.Error].
     */
    fun loadConfig() {
        viewModelScope.launch {
            _state.value = ConfigEditorUiState.Loading
            try {
                val config = configRepository.getConfig()
                _state.value = ConfigEditorUiState.Ready(config = config)
            } catch (e: GatewayException) {
                _state.value = ConfigEditorUiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Records a user edit to a section. Does not write to the gateway.
     *
     * @param section Top-level section key.
     * @param value Full edited section as a [JsonElement].
     */
    fun editSection(section: String, value: JsonElement) {
        val current = _state.value as? ConfigEditorUiState.Ready ?: return
        _state.value = current.copy(
            pendingChanges = current.pendingChanges + (section to value),
            fieldErrors = current.fieldErrors.filterKeys { !it.startsWith("$section.") },
        )
    }

    /**
     * Records or clears a client-side validation error for a specific field path.
     *
     * @param fieldPath Dot-separated path, e.g. `"gateway.http.port"`.
     * @param error Human-readable error message, or `null` to clear.
     */
    fun setFieldError(fieldPath: String, error: String?) {
        val current = _state.value as? ConfigEditorUiState.Ready ?: return
        _state.value = if (error == null) {
            current.copy(fieldErrors = current.fieldErrors - fieldPath)
        } else {
            current.copy(fieldErrors = current.fieldErrors + (fieldPath to error))
        }
    }

    /**
     * Opens the diff confirmation sheet for a section that has pending changes.
     *
     * No-op if the section has no pending changes.
     *
     * @param section Top-level section key.
     */
    fun requestSave(section: String) {
        val current = _state.value as? ConfigEditorUiState.Ready ?: return
        if (!current.pendingChanges.containsKey(section)) return
        _state.value = current.copy(pendingSaveSection = section)
    }

    /**
     * Dismisses the diff sheet without writing to the gateway.
     */
    fun cancelSave() {
        val current = _state.value as? ConfigEditorUiState.Ready ?: return
        _state.value = current.copy(pendingSaveSection = null)
    }

    /**
     * Writes the pending change for [section] to the gateway, then re-reads config.
     *
     * On success: transitions to a fresh [ConfigEditorUiState.Ready] with `saveSuccess = true`.
     * On 412: reloads config and sets `concurrentEditSection`.
     * On 422 / [ConfigValidationException]: sets the field error inline.
     * On other errors: transitions to [ConfigEditorUiState.Error].
     *
     * @param section Top-level section key to write.
     */
    fun confirmSave(section: String) {
        val current = _state.value as? ConfigEditorUiState.Ready ?: return
        val newValue = current.pendingChanges[section] ?: return
        viewModelScope.launch {
            _state.value = current.copy(pendingSaveSection = null)
            try {
                configRepository.updateConfig(section, newValue)
                val updated = configRepository.getConfig()
                _state.value = ConfigEditorUiState.Ready(
                    config = updated,
                    saveSuccess = true,
                )
            } catch (e: GatewayApiException) {
                if (e.statusCode == 412) {
                    val reloaded = try { configRepository.getConfig() } catch (_: Exception) { null }
                    _state.value = if (reloaded != null) {
                        ConfigEditorUiState.Ready(
                            config = reloaded,
                            concurrentEditSection = section,
                        )
                    } else {
                        ConfigEditorUiState.Error(
                            "Concurrent edit detected and reload failed: ${e.message}",
                        )
                    }
                } else {
                    _state.value = ConfigEditorUiState.Error("Save failed: ${e.message}")
                }
            } catch (e: ConfigValidationException) {
                val current2 = _state.value as? ConfigEditorUiState.Ready
                if (current2 != null) {
                    _state.value = current2.copy(
                        fieldErrors = current2.fieldErrors + (e.field to e.reason),
                    )
                } else {
                    _state.value = ConfigEditorUiState.Error("Validation error: ${e.message}")
                }
            } catch (e: GatewayException) {
                _state.value = ConfigEditorUiState.Error("Save failed: ${e.message}")
            }
        }
    }

    /**
     * Discards all pending edits for a section, including any associated field errors.
     *
     * @param section Top-level section key.
     */
    fun discardSection(section: String) {
        val current = _state.value as? ConfigEditorUiState.Ready ?: return
        _state.value = current.copy(
            pendingChanges = current.pendingChanges - section,
            fieldErrors = current.fieldErrors.filterKeys { !it.startsWith("$section.") },
        )
    }

    /** Clears the save-success flag after the snackbar has been shown. */
    fun clearSaveSuccess() {
        val current = _state.value as? ConfigEditorUiState.Ready ?: return
        _state.value = current.copy(saveSuccess = false)
    }

    /** Dismisses the concurrent-edit warning dialog. */
    fun dismissConcurrentEdit() {
        val current = _state.value as? ConfigEditorUiState.Ready ?: return
        _state.value = current.copy(concurrentEditSection = null)
    }
}
