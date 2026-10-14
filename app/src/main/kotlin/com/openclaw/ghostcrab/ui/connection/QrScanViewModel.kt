package com.openclaw.ghostcrab.ui.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openclaw.ghostcrab.domain.util.PairCodeResult
import com.openclaw.ghostcrab.domain.util.decodePairCode
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

sealed interface QrScanUiState {
    /** Initial state before permission is checked. */
    data object Idle : QrScanUiState
    /** Camera is active and scanning. */
    data object Scanning : QrScanUiState
    /** Permission denied once — can request again. */
    data object PermissionRequired : QrScanUiState
    /** Permission permanently denied — must open Settings. */
    data object PermissionDenied : QrScanUiState
    /** A QR was decoded but its content is not a valid gateway URL. */
    data class InvalidQr(val message: String) : QrScanUiState
    /** A valid code was accepted; further frames are ignored. Terminal: navigation leaves the screen. */
    data object Decoded : QrScanUiState
}

sealed interface QrScanEvent {
    /** Emitted when a valid URL is decoded. Navigate to ManualEntry with this URL. */
    data class NavigateToManualEntry(val url: String) : QrScanEvent

    /** A pair bundle was handed to [PairingHandoff]; ManualEntry picks it up (no token in the route). */
    data object NavigateToPairedEntry : QrScanEvent
}

/**
 * ViewModel for [QrScanScreen].
 *
 * All camera permission checks and CameraX lifecycle management live in the screen;
 * this ViewModel only manages state and validates decoded URLs.
 *
 * @param pairingHandoff Receives decoded `ghostcrab://pair` bundles, so the token never enters a route.
 */
class QrScanViewModel(private val pairingHandoff: PairingHandoff) : ViewModel() {

    private val _uiState = MutableStateFlow<QrScanUiState>(QrScanUiState.Idle)
    val uiState: StateFlow<QrScanUiState> = _uiState.asStateFlow()

    private val _events = Channel<QrScanEvent>(Channel.BUFFERED)
    /** One-shot navigation events, buffered so an event sent during rotation is delivered once a collector returns. */
    val events: Flow<QrScanEvent> = _events.receiveAsFlow()

    /** Call when the system grants camera permission. */
    fun onPermissionGranted() {
        _uiState.value = QrScanUiState.Scanning
    }

    /**
     * Call when the system denies camera permission.
     *
     * @param isPermanent `true` when "Don't ask again" was selected.
     */
    fun onPermissionDenied(isPermanent: Boolean) {
        _uiState.value = if (isPermanent) QrScanUiState.PermissionDenied
                          else QrScanUiState.PermissionRequired
    }

    /**
     * Call when ML Kit decodes a barcode. Accepts a plain `http(s)` gateway URL (emits
     * [QrScanEvent.NavigateToManualEntry]) or a `ghostcrab://pair` bundle (offered to [PairingHandoff],
     * emits [QrScanEvent.NavigateToPairedEntry]); anything else sets [QrScanUiState.InvalidQr]. After one
     * accepted code, further frames are ignored until [onScanAgain].
     *
     * @param raw Raw string decoded from the QR code.
     */
    fun onQrDecoded(raw: String) {
        if (_uiState.value == QrScanUiState.Decoded) return
        when (val result = decodePairCode(raw)) {
            is PairCodeResult.Invalid -> _uiState.value = QrScanUiState.InvalidQr(result.reason)
            // Navigate with the normalized base URL only: path/query/fragment must not reach the saved-state Bundle.
            is PairCodeResult.PlainUrl -> accept(QrScanEvent.NavigateToManualEntry(result.url.toString()))
            is PairCodeResult.Bundle -> {
                pairingHandoff.offer(result.bundle)
                accept(QrScanEvent.NavigateToPairedEntry)
            }
        }
    }

    private fun accept(event: QrScanEvent) {
        _uiState.value = QrScanUiState.Decoded
        viewModelScope.launch { _events.send(event) }
    }

    /** Re-activate the scanner after a [QrScanUiState.InvalidQr] state. */
    fun onScanAgain() {
        _uiState.value = QrScanUiState.Scanning
    }
}
