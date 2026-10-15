package com.openclaw.ghostcrab.ui.config

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openclaw.ghostcrab.domain.exception.ConfigValidationException
import com.openclaw.ghostcrab.domain.exception.GatewayApiException
import com.openclaw.ghostcrab.domain.exception.GatewayException
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import com.openclaw.ghostcrab.domain.model.OpenClawConfig
import com.openclaw.ghostcrab.domain.repository.ConfigRepository
import com.openclaw.ghostcrab.domain.repository.GatewayConnectionManager
import com.openclaw.ghostcrab.domain.util.suspendRunCatching
import com.openclaw.ghostcrab.ui.config.sections.inlineErrorFieldsOf
import com.openclaw.ghostcrab.ui.toUserMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
                _state.value = if (config.sections.isEmpty()) {
                    ConfigEditorUiState.NoConfigApi
                } else {
                    ConfigEditorUiState.Ready(config = config)
                }
            } catch (e: GatewayException) {
                _state.value = ConfigEditorUiState.Error(e.toUserMessage())
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
        _state.update { state ->
            if (state !is ConfigEditorUiState.Ready) return@update state
            state.copy(
                pendingChanges = state.pendingChanges + (section to value),
                fieldErrors = state.fieldErrors.filterKeys { !it.startsWith("$section.") },
            )
        }
    }

    /**
     * Records or clears a client-side validation error for a specific field path.
     *
     * @param fieldPath Dot-separated path, e.g. `"gateway.port"`.
     * @param error Human-readable error message, or `null` to clear.
     */
    fun setFieldError(fieldPath: String, error: String?) {
        _state.update { state ->
            if (state !is ConfigEditorUiState.Ready) return@update state
            if (error == null) state.copy(fieldErrors = state.fieldErrors - fieldPath)
            else state.copy(fieldErrors = state.fieldErrors + (fieldPath to error))
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
        _state.update { state ->
            if (state !is ConfigEditorUiState.Ready || !state.pendingChanges.containsKey(section)) return@update state
            state.copy(pendingSaveSection = section)
        }
    }

    /**
     * Dismisses the diff sheet without writing to the gateway.
     */
    fun cancelSave() {
        _state.update { (it as? ConfigEditorUiState.Ready)?.copy(pendingSaveSection = null) ?: it }
    }

    /**
     * Writes the pending change for [section] to the gateway, then re-reads config.
     *
     * Every outcome copies the latest [ConfigEditorUiState.Ready] instead of rebuilding it, so edits
     * and diff sheets opened while the save was in flight survive.
     * On success: reloads config, drops [section]'s pending edit only if it is still the saved value,
     *   sets saveSuccess.
     * On 412: reloads config, keeps every pending edit, sets `concurrentEditSection`.
     * On [ConfigValidationException] (the gateway's schema check, one issue inside [section]): sets the field
     *   error, and also `saveError` when the section's editor has no field showing that path (raw JSON sections
     *   always), so a rejection is never silent.
     * On other errors, 422 included (or a failed reload after 412): keeps every pending edit, sets `saveError`.
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
                _state.updateReady { it.afterSave(section, newValue, updated) }
            } catch (e: GatewayApiException) {
                if (e.statusCode == 412) {
                    val reloaded = suspendRunCatching { configRepository.getConfig() }
                    _state.updateReady { ready ->
                        reloaded.fold(
                            onSuccess = { ready.copy(config = it, concurrentEditSection = section) },
                            onFailure = {
                                ready.copy(
                                    saveError = "Concurrent edit detected and reload failed: ${it.toUserMessage()}",
                                )
                            },
                        )
                    }
                } else {
                    _state.updateReady { it.copy(saveError = "Save failed: ${e.toUserMessage()}") }
                }
            } catch (e: ConfigValidationException) {
                _state.updateReady { it.withValidationError(section, newValue, e) }
            } catch (e: GatewayException) {
                _state.updateReady { it.copy(saveError = "Save failed: ${e.toUserMessage()}") }
            }
        }
    }

    /**
     * Discards all pending edits for a section, including any associated field errors.
     *
     * @param section Top-level section key.
     */
    fun discardSection(section: String) {
        _state.update { state ->
            if (state !is ConfigEditorUiState.Ready) return@update state
            state.copy(
                pendingChanges = state.pendingChanges - section,
                fieldErrors = state.fieldErrors.filterKeys { !it.startsWith("$section.") },
            )
        }
    }

    /** Clears the save-success flag after the snackbar has been shown. */
    fun clearSaveSuccess() {
        _state.update { (it as? ConfigEditorUiState.Ready)?.copy(saveSuccess = false) ?: it }
    }

    /** Clears [ConfigEditorUiState.Ready.saveError] after the snackbar has been shown. */
    fun clearSaveError() {
        _state.update { (it as? ConfigEditorUiState.Ready)?.copy(saveError = null) ?: it }
    }

    /**
     * Dismisses the concurrent-edit warning dialog.
     *
     * @param discardConflicting When `true` (the dialog's Reload), also drops the pending edit of the
     *   conflicting section; the latest server config is already loaded and other sections' edits stay.
     */
    fun dismissConcurrentEdit(discardConflicting: Boolean = false) {
        _state.update { state ->
            val ready = state as? ConfigEditorUiState.Ready ?: return@update state
            val section = ready.concurrentEditSection
            val dismissed = ready.copy(concurrentEditSection = null)
            if (discardConflicting && section != null) {
                dismissed.copy(
                    pendingChanges = dismissed.pendingChanges - section,
                    fieldErrors = dismissed.fieldErrors.filterKeys { !it.startsWith("$section.") },
                )
            } else {
                dismissed
            }
        }
    }
}

/** Applies [transform] when the state is [ConfigEditorUiState.Ready]; any other state is left as is. */
private inline fun MutableStateFlow<ConfigEditorUiState>.updateReady(
    transform: (ConfigEditorUiState.Ready) -> ConfigEditorUiState.Ready,
) {
    update { state -> (state as? ConfigEditorUiState.Ready)?.let { transform(it) } ?: state }
}

/**
 * State after the gateway rejected [section] (saved as [savedValue]) with [error]: the field error is recorded, and
 * when the editor rendering the section's current value has no field for that path, it is also a save error.
 */
private fun ConfigEditorUiState.Ready.withValidationError(
    section: String,
    savedValue: JsonElement,
    error: ConfigValidationException,
): ConfigEditorUiState.Ready {
    val shownInline = error.field in inlineErrorFieldsOf(section, pendingChanges[section] ?: savedValue)
    return copy(
        fieldErrors = fieldErrors + (error.field to error.reason),
        saveError = if (shownInline) saveError else "Save failed: ${error.toUserMessage()}",
    )
}

/**
 * State after [section] was written as [savedValue] and the config re-read as [updated].
 *
 * The section's pending edit and field errors are dropped only while they still match what was
 * saved; a re-edit made during the save survives. Other sections and the open diff sheet are kept.
 */
private fun ConfigEditorUiState.Ready.afterSave(
    section: String,
    savedValue: JsonElement,
    updated: OpenClawConfig,
): ConfigEditorUiState.Ready {
    val unchangedSinceSave = pendingChanges[section] == savedValue
    return copy(
        config = updated,
        pendingChanges = if (unchangedSinceSave) pendingChanges - section else pendingChanges,
        fieldErrors = if (unchangedSinceSave) fieldErrors.filterKeys { !it.startsWith("$section.") } else fieldErrors,
        saveSuccess = true,
    )
}
