package com.openclaw.ghostcrab.ui.model

import com.openclaw.ghostcrab.domain.model.ModelInfo

/**
 * Exhaustive UI state for the Model Manager screen.
 *
 * State transitions:
 * - App start / reload: [Loading]
 * - No active gateway connection: [Disconnected]
 * - Network/API failure: [Error]
 * - Models loaded successfully: [Ready]
 */
public sealed interface ModelManagerUiState {

    /** Fetching models from the gateway. */
    public data object Loading : ModelManagerUiState

    /** No active gateway connection — screen should navigate back. */
    public data object Disconnected : ModelManagerUiState

    /**
     * Network or API error occurred.
     *
     * @param message Human-readable error detail (URL, HTTP status, exception class).
     */
    public data class Error(val message: String) : ModelManagerUiState

    /**
     * Models loaded and ready to display.
     *
     * @param models Ordered list of models from the gateway.
     * @param pendingSwapId ID of the model for which the swap confirmation dialog is showing; null = no dialog.
     * @param isSwapping True while the swap POST is in flight.
     * @param swapSuccess True when "Active model updated" snackbar should be shown.
     * @param swapError Non-null when a swap failure snackbar should be shown; cleared by [ModelManagerViewModel.clearSwapError].
     */
    public data class Ready(
        val models: List<ModelInfo>,
        val pendingSwapId: String? = null,
        val isSwapping: Boolean = false,
        val swapSuccess: Boolean = false,
        val swapError: String? = null,
    ) : ModelManagerUiState
}
