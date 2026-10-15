package com.openclaw.ghostcrab.ui.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openclaw.ghostcrab.domain.model.DiscoveredGateway
import com.openclaw.ghostcrab.domain.repository.ConnectionProfileRepository
import com.openclaw.ghostcrab.domain.repository.DiscoveryService
import com.openclaw.ghostcrab.domain.repository.GatewayConnectionManager
import com.openclaw.ghostcrab.domain.util.suspendRunCatching
import com.openclaw.ghostcrab.ui.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
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
 * @param cleanupScope Where the approval wait's disconnect runs once this ViewModel is cleared (see
 *   [PairingWait.release]); tests keep the default and drive time with `Dispatchers.setMain`.
 */
class ScanViewModel(
    private val discoveryService: DiscoveryService,
    private val profileRepository: ConnectionProfileRepository,
    private val connectionManager: GatewayConnectionManager,
    cleanupScope: CoroutineScope = pairingCleanupScope(),
) : ViewModel() {

    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)

    /** Current scan state. */
    val state: StateFlow<ScanState> = _state.asStateFlow()

    private val _events = Channel<ScanEvent>(Channel.BUFFERED)

    /** One-shot navigation events, buffered so an event sent during rotation is delivered once a collector returns. */
    val events: Flow<ScanEvent> = _events.receiveAsFlow()

    private val _pairingPrompt = MutableStateFlow<PairingPrompt?>(null)

    /** Set while a saved gateway waits for this device to be approved (ADR-0007); the screen shows the panel. */
    val pairingPrompt: StateFlow<PairingPrompt?> = _pairingPrompt.asStateFlow()

    /**
     * The saved gateway's connect and its approval wait: one at a time. The approval panel's Check now / Cancel go
     * here, and the screen cancels it before it navigates away (the wait also ends when the ViewModel is cleared).
     */
    val pairing: PairingWait =
        PairingWait(viewModelScope, connectionManager, cleanupScope) { _pairingPrompt.value = null }

    private var scanJob: Job? = null

    init {
        // The screen leaves the back stack mid-wait (e.g. system Back): same cleanup as PairingWait.cancel.
        addCloseable { pairing.release() }
    }

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
                _state.value = ScanState.Error(e.toUserMessage())
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
     *   [ScanEvent.NavigateToDashboard]. On `PAIRING_REQUIRED` (e.g. a regenerated device identity or a scope
     *   upgrade) sets [pairingPrompt] and retries until the device is approved, the pending request expires (an
     *   explicit [ScanState.Error]) or [PairingWait.cancel]. Ignored while such a connect is already running.
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
                // Read once, before the wait: a Keystore read per retry is wasteful, and a failure mid-wait would
                // leave a stale PAIRING_REQUIRED error. getToken() fails with ProfileNeedsReauthException after a
                // Keystore reset; nothing has connected yet, so there is nothing to disconnect.
                val token = suspendRunCatching { if (match.hasToken) profileRepository.getToken(match.id) else null }
                    .getOrElse { e ->
                        _state.value = ScanState.Error(e.toUserMessage())
                        return@launch
                    }
                pairing.start(
                    url = match.url,
                    onPrompt = { _pairingPrompt.value = it },
                    connect = { connectionManager.connect(match.url, token) },
                    onFinished = ::onConnectFinished,
                )
            } else {
                _events.send(ScanEvent.NavigateToManualEntry(gateway.url))
            }
        }
    }

    /** Navigates on success; otherwise shows the expired approval window or the failure. */
    private suspend fun onConnectFinished(result: Result<Boolean>) {
        _pairingPrompt.value = null
        result.onSuccess { approved ->
            if (approved) {
                _events.send(ScanEvent.NavigateToDashboard)
            } else {
                _state.value = ScanState.Error(PAIRING_TIMEOUT_MESSAGE)
            }
        }.onFailure { e ->
            _state.value = ScanState.Error(e.toUserMessage())
        }
    }

    override fun onCleared() {
        super.onCleared()
        scanJob?.cancel()
    }
}
