package com.openclaw.ghostcrab.ui.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openclaw.ghostcrab.domain.model.DiscoveredGateway
import com.openclaw.ghostcrab.domain.repository.ConnectionProfileRepository
import com.openclaw.ghostcrab.domain.repository.DiscoveryService
import com.openclaw.ghostcrab.domain.repository.GatewayConnectionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val SCAN_DURATION_MS = 10_000L

/**
 * State machine for the LAN discovery screen.
 *
 * Transitions:
 * ```
 * Idle → Scanning → Results(scanCompleted=false) → Results(scanCompleted=true)
 *                ↘ Error
 * ```
 * The [Results] state is emitted as soon as the first gateway is found; [Results.scanCompleted]
 * becomes `true` when the 10s auto-stop fires or [stopScan] is called explicitly.
 */
sealed interface ScanState {
    data object Idle : ScanState

    /**
     * NSD discovery is active. [elapsedMs] is available for progress indicators but the screen
     * uses an infinite animation instead.
     */
    data class Scanning(val elapsedMs: Long = 0L) : ScanState

    /**
     * @param gateways Resolved gateways accumulated so far.
     * @param scanCompleted `false` while the scan is still running; `true` once it has stopped.
     */
    data class Results(
        val gateways: List<DiscoveredGateway>,
        val scanCompleted: Boolean,
    ) : ScanState

    /** Discovery failed before any results could be collected. */
    data class Error(val reason: String) : ScanState
}

/**
 * One-shot events emitted for navigation.
 */
sealed interface ScanEvent {
    /** Gateway is not in saved profiles → navigate to ManualEntry with URL pre-filled. */
    data class NavigateToManualEntry(val prefillUrl: String) : ScanEvent

    /** Gateway matched a saved profile and connection succeeded → navigate to Dashboard. */
    data object NavigateToDashboard : ScanEvent
}

/**
 * ViewModel for [ScanScreen].
 *
 * @param discoveryService NsdManager-backed mDNS scanner.
 * @param profileRepository Used to match discovered gateways against saved profiles.
 * @param connectionManager Used to connect to previously seen gateways without re-entering creds.
 */
class ScanViewModel(
    private val discoveryService: DiscoveryService,
    private val profileRepository: ConnectionProfileRepository,
    private val connectionManager: GatewayConnectionManager,
) : ViewModel() {

    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)

    /** Current scan state. */
    val state: StateFlow<ScanState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ScanEvent>()

    /** One-shot navigation events. Collect in the composable. */
    val events: SharedFlow<ScanEvent> = _events.asSharedFlow()

    private var scanJob: Job? = null

    /**
     * Starts a fresh scan, cancelling any scan that is currently in progress.
     * Auto-stops after 10 s via [SCAN_DURATION_MS].
     */
    fun startScan() {
        scanJob?.cancel()
        _state.value = ScanState.Scanning()

        scanJob = viewModelScope.launch {
            val gathered = mutableListOf<DiscoveredGateway>()

            // Auto-stop: close the discovery channel after SCAN_DURATION_MS
            val timeoutJob = launch {
                delay(SCAN_DURATION_MS)
                discoveryService.stopDiscovery()
            }

            try {
                discoveryService.startDiscovery().collect { gateway ->
                    gathered += gateway
                    _state.value = ScanState.Results(
                        gateways = gathered.toList(),
                        scanCompleted = false,
                    )
                }
                // Flow ended normally (stopDiscovery closed the channel)
                timeoutJob.cancel()
                _state.value = ScanState.Results(
                    gateways = gathered.toList(),
                    scanCompleted = true,
                )
            } catch (e: CancellationException) {
                timeoutJob.cancel()
                throw e // propagate so the Job hierarchy can cancel cleanly
            } catch (e: Exception) {
                timeoutJob.cancel()
                _state.value = ScanState.Error(e.message ?: "Discovery failed")
            }
        }
    }

    /**
     * Stops the active scan immediately. No-op if not scanning.
     */
    fun stopScan() {
        scanJob?.cancel()
        viewModelScope.launch { discoveryService.stopDiscovery() }
        val current = _state.value
        if (current is ScanState.Scanning) {
            _state.value = ScanState.Results(emptyList(), scanCompleted = true)
        } else if (current is ScanState.Results && !current.scanCompleted) {
            _state.value = current.copy(scanCompleted = true)
        }
    }

    /**
     * Handles a gateway selection from the results list.
     *
     * - If [gateway] matches a saved profile by URL, connects with stored credentials and emits
     *   [ScanEvent.NavigateToDashboard].
     * - Otherwise emits [ScanEvent.NavigateToManualEntry] so the user can confirm the URL and
     *   optionally supply a token.
     *
     * @param gateway The gateway the user tapped.
     */
    fun onGatewaySelected(gateway: DiscoveredGateway) {
        viewModelScope.launch {
            val profiles = profileRepository.getProfiles().first()
            val match = profiles.find { it.url == gateway.url }

            if (match != null) {
                val token = profileRepository.getToken(match.id)
                runCatching { connectionManager.connect(match.url, token) }
                    .onSuccess { _events.emit(ScanEvent.NavigateToDashboard) }
                    .onFailure { e ->
                        _state.value = ScanState.Error(e.message ?: "Connection failed")
                    }
            } else {
                _events.emit(ScanEvent.NavigateToManualEntry(gateway.url))
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        scanJob?.cancel()
    }
}
