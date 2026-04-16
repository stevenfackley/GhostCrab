package com.openclaw.ghostcrab.ui.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openclaw.ghostcrab.domain.exception.GatewayException
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import com.openclaw.ghostcrab.domain.repository.GatewayConnectionManager
import com.openclaw.ghostcrab.domain.repository.ModelRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for the Model Manager screen.
 *
 * Observes [GatewayConnectionManager.connectionState] and loads models whenever a [GatewayConnection.Connected]
 * state is emitted. Exposes [state] as an immutable [StateFlow] of [ModelManagerUiState].
 *
 * @param connectionManager Provides the live connection state flow.
 * @param modelRepository Source for model list and active-model mutation.
 */
public class ModelManagerViewModel(
    private val connectionManager: GatewayConnectionManager,
    private val modelRepository: ModelRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<ModelManagerUiState>(ModelManagerUiState.Loading)

    /** Observable UI state. */
    public val state: StateFlow<ModelManagerUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            connectionManager.connectionState.collect { conn ->
                when (conn) {
                    is GatewayConnection.Connected -> loadModels()
                    is GatewayConnection.Disconnected -> _state.value = ModelManagerUiState.Disconnected
                    else -> Unit
                }
            }
        }
    }

    /**
     * Triggers a fresh fetch of the model list from the gateway.
     *
     * Sets [ModelManagerUiState.Loading] immediately, then transitions to
     * [ModelManagerUiState.Ready] or [ModelManagerUiState.Error].
     */
    public fun loadModels() {
        viewModelScope.launch {
            _state.value = ModelManagerUiState.Loading
            try {
                val models = modelRepository.getModels()
                _state.value = ModelManagerUiState.Ready(models = models)
            } catch (e: GatewayException) {
                _state.value = ModelManagerUiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Opens the swap confirmation dialog for [modelId].
     *
     * No-op if the model is already active or not found in the current list.
     *
     * @param modelId The model to request activation for.
     */
    public fun requestSwap(modelId: String) {
        _state.update { state ->
            if (state !is ModelManagerUiState.Ready) return@update state
            val model = state.models.find { it.id == modelId } ?: return@update state
            if (model.isActive) return@update state
            state.copy(pendingSwapId = modelId)
        }
    }

    /**
     * Dismisses the swap confirmation dialog without performing any action.
     */
    public fun cancelSwap() {
        _state.update { (it as? ModelManagerUiState.Ready)?.copy(pendingSwapId = null) ?: it }
    }

    /**
     * Performs the active-model swap after user confirmation.
     *
     * On success: reloads models and sets [ModelManagerUiState.Ready.swapSuccess] = true.
     * On failure: surfaces an [ModelManagerUiState.Error] then recovers to [ModelManagerUiState.Ready]
     * with the best available model list.
     *
     * @param modelId The model to activate.
     */
    public fun confirmSwap(modelId: String) {
        val current = _state.value as? ModelManagerUiState.Ready ?: return
        viewModelScope.launch {
            _state.value = current.copy(pendingSwapId = null, isSwapping = true)
            try {
                modelRepository.setActiveModel(modelId)
                val updated = modelRepository.getModels()
                _state.value = ModelManagerUiState.Ready(
                    models = updated,
                    swapSuccess = true,
                )
            } catch (e: GatewayException) {
                val recovered = try {
                    modelRepository.getModels()
                } catch (_: Exception) {
                    current.models
                }
                _state.value = ModelManagerUiState.Ready(
                    models = recovered,
                    swapError = "Failed to swap model: ${e.message}",
                )
            }
        }
    }

    /**
     * Clears the swap-success snackbar flag after it has been shown.
     */
    public fun clearSwapSuccess() {
        _state.update { (it as? ModelManagerUiState.Ready)?.copy(swapSuccess = false) ?: it }
    }

    /**
     * Clears the swap-error snackbar message after it has been shown.
     */
    public fun clearSwapError() {
        _state.update { (it as? ModelManagerUiState.Ready)?.copy(swapError = null) ?: it }
    }
}
