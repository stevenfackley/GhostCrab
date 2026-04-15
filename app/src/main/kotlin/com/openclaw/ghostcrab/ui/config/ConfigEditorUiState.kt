package com.openclaw.ghostcrab.ui.config

import com.openclaw.ghostcrab.domain.model.OpenClawConfig
import kotlinx.serialization.json.JsonElement

/**
 * UI state for the Config Editor screen.
 *
 * The gateway is always the source of truth — there is no optimistic local update.
 */
sealed interface ConfigEditorUiState {

    /** Initial state while the config is being fetched from the gateway. */
    data object Loading : ConfigEditorUiState

    /** No active gateway connection. Screen should navigate back. */
    data object Disconnected : ConfigEditorUiState

    /**
     * A non-recoverable error occurred (other than concurrency conflict).
     *
     * @param message Exact error text including URL, status code, and exception class where applicable.
     */
    data class Error(val message: String) : ConfigEditorUiState

    /**
     * Config loaded and ready for display / editing.
     *
     * @param config The authoritative config as returned by the gateway.
     * @param pendingChanges Section key → full edited [JsonElement]. Only sections with unsaved edits are present.
     * @param fieldErrors Field path → error message (e.g. `"gateway.http.port"` → `"Must be 1–65535"`).
     * @param pendingSaveSection When non-null, the diff sheet is shown for this section key.
     * @param saveSuccess When `true`, show a success snackbar; call [com.openclaw.ghostcrab.ui.config.ConfigEditorViewModel.clearSaveSuccess] to dismiss.
     * @param concurrentEditSection When non-null, show a concurrent-edit warning dialog for this section.
     */
    data class Ready(
        val config: OpenClawConfig,
        val pendingChanges: Map<String, JsonElement> = emptyMap(),
        val fieldErrors: Map<String, String> = emptyMap(),
        val pendingSaveSection: String? = null,
        val saveSuccess: Boolean = false,
        val concurrentEditSection: String? = null,
    ) : ConfigEditorUiState
}
