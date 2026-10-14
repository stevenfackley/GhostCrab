package com.openclaw.ghostcrab.ui.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openclaw.ghostcrab.domain.util.GatewayUrl
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
}

/**
 * ViewModel for [QrScanScreen].
 *
 * All camera permission checks and CameraX lifecycle management live in the screen;
 * this ViewModel only manages state and validates decoded URLs.
 */
class QrScanViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<QrScanUiState>(QrScanUiState.Idle)
    val uiState: StateFlow<QrScanUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<QrScanEvent>()
    val events: SharedFlow<QrScanEvent> = _events.asSharedFlow()

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
     * Call when ML Kit decodes a barcode.
     *
     * Validates [raw] is a valid `http://` or `https://` URL. On success emits
     * [QrScanEvent.NavigateToManualEntry]; on failure sets [QrScanUiState.InvalidQr].
     *
     * @param raw Raw string decoded from the QR code.
     */
    fun onQrDecoded(raw: String) {
        if (_uiState.value == QrScanUiState.Decoded) return
        // Navigate with the normalized base URL only: path/query/fragment must not reach the saved-state Bundle.
        val url = GatewayUrl.parse(raw.trim())?.toString()
        if (url == null) {
            _uiState.value = QrScanUiState.InvalidQr("QR code doesn't contain a valid gateway URL")
            return
        }
        _uiState.value = QrScanUiState.Decoded
        viewModelScope.launch { _events.emit(QrScanEvent.NavigateToManualEntry(url)) }
    }

    /** Re-activate the scanner after a [QrScanUiState.InvalidQr] state. */
    fun onScanAgain() {
        _uiState.value = QrScanUiState.Scanning
    }
}
