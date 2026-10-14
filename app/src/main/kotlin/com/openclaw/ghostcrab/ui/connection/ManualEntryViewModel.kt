package com.openclaw.ghostcrab.ui.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openclaw.ghostcrab.domain.model.ConnectionProfile
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import com.openclaw.ghostcrab.domain.repository.ConnectionProfileRepository
import com.openclaw.ghostcrab.domain.repository.GatewayConnectionManager
import com.openclaw.ghostcrab.domain.repository.OnboardingRepository
import com.openclaw.ghostcrab.domain.util.DEFAULT_GATEWAY_PORT
import com.openclaw.ghostcrab.domain.util.GatewayUrl
import com.openclaw.ghostcrab.domain.util.suspendRunCatching
import com.openclaw.ghostcrab.ui.toUserMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class ManualEntryFormState(
    val useHttps: Boolean = false,
    val host: String = "",
    val port: String = DEFAULT_GATEWAY_PORT.toString(),
    val token: String = "",
    val tokenVisible: Boolean = false,
    val hostError: String? = null,
    val portError: String? = null,
) {
    val scheme: String get() = if (useHttps) "https" else "http"
    val assembledUrl: String
        get() {
            val h = host.trim()
            val p = port.trim()
            // Canonical form (e.g. port "08080" -> 8080) so dedup and saved-profile matching agree.
            // Falls back to the raw text while the form is still incomplete (live preview).
            return p.toIntOrNull()?.let { GatewayUrl.ofOrNull(h, it, useHttps)?.toString() }
                ?: "$scheme://${if (h.contains(':')) "[$h]" else h}:$p"
        }

    /** Redacts [token] so form state can be logged or dumped without leaking a bearer. */
    override fun toString(): String =
        "ManualEntryFormState(useHttps=$useHttps, host=$host, port=$port, " +
            "token=${if (token.isEmpty()) "" else "[REDACTED]"}, tokenVisible=$tokenVisible, " +
            "hostError=$hostError, portError=$portError)"
}

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
    private val onboardingRepository: OnboardingRepository,
    private val pairingHandoff: PairingHandoff,
) : ViewModel() {

    /** Display name from a consumed pair bundle; used for the saved profile instead of the gateway's own name. */
    private var pairedName: String? = null

    /** Bumped on every [PairingHandoff.offer]; the screen re-runs [consumePendingPairing] when it changes. */
    val pairingOffers: StateFlow<Int> = pairingHandoff.offers

    private val _form = MutableStateFlow(ManualEntryFormState())
    val form: StateFlow<ManualEntryFormState> = _form.asStateFlow()

    private val _uiState = MutableStateFlow<ManualEntryUiState>(ManualEntryUiState.Idle)
    val uiState: StateFlow<ManualEntryUiState> = _uiState.asStateFlow()

    private val _events = Channel<ManualEntryEvent>(Channel.BUFFERED)
    /** One-shot navigation events, buffered so an event sent during rotation is delivered once a collector returns. */
    val events: Flow<ManualEntryEvent> = _events.receiveAsFlow()

    fun onHostChange(value: String) {
        _form.update { it.copy(host = value, hostError = null) }
    }

    fun onPortChange(value: String) {
        val digitsOnly = value.filter(Char::isDigit).take(5)
        _form.update { it.copy(port = digitsOnly, portError = null) }
    }

    fun onHttpsToggle(useHttps: Boolean) {
        _form.update { it.copy(useHttps = useHttps) }
    }

    /** Parses a full URL (LAN scan, QR) and populates scheme/host/port individually. */
    fun setPrefillUrl(url: String) {
        val parsed = GatewayUrl.parse(url)
        _form.update {
            it.copy(
                useHttps = parsed?.scheme == "https",
                host = parsed?.host.orEmpty(),
                port = (parsed?.port ?: DEFAULT_GATEWAY_PORT).toString(),
                hostError = null,
                portError = null,
            )
        }
    }

    /**
     * Consumes a pending [com.openclaw.ghostcrab.domain.util.PairBundle] (QR or `ghostcrab://pair`
     * deep link) and prefills the form. Never connects by itself: the user confirms with Connect.
     *
     * @return `true` if a bundle was consumed; `false` when none was pending (e.g. on recomposition).
     */
    fun consumePendingPairing(): Boolean {
        val bundle = pairingHandoff.take() ?: return false
        pairedName = bundle.name?.takeIf { it.isNotBlank() }
        _form.update {
            it.copy(
                useHttps = bundle.url.scheme == "https",
                host = bundle.url.host,
                port = bundle.url.port.toString(),
                token = bundle.token.orEmpty(),
                hostError = null,
                portError = null,
            )
        }
        return true
    }

    fun onTokenChange(value: String) {
        _form.update { it.copy(token = value) }
    }

    fun toggleTokenVisibility() {
        _form.update { it.copy(tokenVisible = !it.tokenVisible) }
    }

    fun connect() {
        val state = _form.value
        val hostError = validateHost(state.host)
        val portError = validatePort(state.port)
        if (hostError != null || portError != null) {
            _form.update { it.copy(hostError = hostError, portError = portError) }
            return
        }

        val url = state.assembledUrl
        viewModelScope.launch {
            _uiState.value = ManualEntryUiState.Connecting
            val token = state.token.trim().takeIf { it.isNotEmpty() }
            // Connect AND persist inside one guard: a Keystore/DataStore failure after a successful
            // connect must surface as an error, not crash the app.
            suspendRunCatching {
                connectionManager.connect(url, token)
                saveConnectedProfile(url, token)
                onboardingRepository.markCompleted()
            }.onSuccess {
                _uiState.value = ManualEntryUiState.Idle
                _events.send(ManualEntryEvent.NavigateToDashboard)
            }.onFailure { e ->
                _uiState.value = ManualEntryUiState.Error(e.toUserMessage())
            }
        }
    }

    private suspend fun saveConnectedProfile(url: String, token: String?) {
        val displayName = pairedName
            ?: (connectionManager.connectionState.value as? GatewayConnection.Connected)?.displayName
            ?: url
        val existingId = profileRepository.getProfiles().first().firstOrNull { it.url == url }?.id
        profileRepository.saveProfile(
            ConnectionProfile(
                id = existingId ?: UUID.randomUUID().toString(),
                displayName = displayName,
                url = url,
                lastConnectedAt = System.currentTimeMillis(),
                hasToken = token != null,
            ),
            token,
        )
    }
}

@Suppress("ReturnCount")
private fun validateHost(host: String): String? {
    val trimmed = host.trim()
    if (trimmed.isEmpty()) return "Host is required"
    if (trimmed.contains("://")) return "Remove scheme — use the HTTPS toggle instead"
    if (trimmed.contains('/')) return "Host must not contain '/'"
    // IPv6 literal (from LAN discovery or QR): hex digits, dots and at least two colons, no brackets.
    if (isIpv6Literal(trimmed)) return null
    // Zone ids (fe80::1%wlan0) are IPv6-only; elsewhere GatewayUrl would drop '%...' silently.
    if (trimmed.contains('%')) {
        return if (isIpv6WithZone(trimmed)) null else "Host must not contain '%' (IPv6 zone ids only)"
    }
    if (trimmed.contains(':')) return "Port goes in the Port field, not the Host field"
    if (trimmed.any { it.isWhitespace() }) return "Host must not contain whitespace"
    // '@' would smuggle userinfo into the assembled URL; '?' and '#' would truncate the host.
    if (trimmed.any { it in "@?#\\" }) return "Host must not contain '@', '?', '#' or '\\'"
    return null
}

private fun isIpv6Literal(host: String): Boolean =
    host.count { it == ':' } >= 2 && host.all { it == ':' || it == '.' || isHexDigit(it) }

private fun isIpv6WithZone(host: String): Boolean {
    val zone = host.substringAfter('%')
    return isIpv6Literal(host.substringBefore('%')) && zone.isNotEmpty() &&
        zone.all { it.isLetterOrDigit() || it in "-_." }
}

private fun isHexDigit(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

@Suppress("ReturnCount")
private fun validatePort(port: String): String? {
    val trimmed = port.trim()
    if (trimmed.isEmpty()) return "Port is required"
    val n = trimmed.toIntOrNull() ?: return "Port must be a number"
    if (n < 1 || n > 65535) return "Port must be between 1 and 65535"
    return null
}
