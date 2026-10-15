package com.openclaw.ghostcrab.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import com.openclaw.ghostcrab.domain.repository.GatewayConnectionManager
import com.openclaw.ghostcrab.domain.repository.GatewayHealthChecker
import com.openclaw.ghostcrab.domain.repository.ModelRepository
import com.openclaw.ghostcrab.domain.util.suspendRunCatching
import com.openclaw.ghostcrab.ui.sessionChanges
import com.openclaw.ghostcrab.ui.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the connected-gateway dashboard.
 *
 * Combines [GatewayConnectionManager.connectionState], periodic `/health` polls every
 * [POLL_INTERVAL_MS], and a one-shot [ModelRepository.getModels] fetch into a single
 * [DashboardUiState] stream.
 *
 * **Health degradation:** two consecutive poll failures flip the state to [DashboardUiState.Degraded].
 * Polling stops while the ViewModel is cleared (screen leaves the back stack).
 *
 * @param connectionManager Source of [GatewayConnection] state transitions.
 * @param modelRepository Fetches the gateway's model list (best-effort; empty on failure).
 * @param healthChecker Runs GET /health on the live session client. Injected for testability.
 */
class DashboardViewModel(
    private val connectionManager: GatewayConnectionManager,
    private val modelRepository: ModelRepository,
    private val healthChecker: GatewayHealthChecker,
) : ViewModel() {

    private val _state = MutableStateFlow<DashboardUiState>(DashboardUiState.Loading)
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    private var pollJob: Job? = null

    init {
        viewModelScope.launch {
            // A silent reconnect keeps the dashboard (and its polling) as is; the reconnected session restarts it.
            connectionManager.connectionState.sessionChanges().collect { conn ->
                pollJob?.cancel()
                when (conn) {
                    is GatewayConnection.Connected -> startDashboard(conn)
                    is GatewayConnection.Connecting -> _state.value = DashboardUiState.Loading
                    is GatewayConnection.Disconnected,
                    is GatewayConnection.Error -> _state.value = DashboardUiState.Disconnected
                }
            }
        }
    }

    // ── Public actions ────────────────────────────────────────────────────────

    /** Initiates a graceful disconnect; UI should react to the resulting [DashboardUiState.Disconnected]. */
    fun disconnect() {
        viewModelScope.launch { connectionManager.disconnect() }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun startDashboard(conn: GatewayConnection.Connected) {
        _state.value = DashboardUiState.Loading
        pollJob = viewModelScope.launch {
            val models = suspendRunCatching { modelRepository.getModels() }.getOrDefault(emptyList())

            // Emit Ready immediately — satisfies "renders within 1 s" acceptance criterion
            _state.value = DashboardUiState.Ready(
                connection = conn,
                health = HealthSnapshot(lastOkMs = null, lastError = null),
                models = models,
            )

            // Health poll: immediate first check, then every POLL_INTERVAL_MS
            var consecutiveFailures = 0
            while (isActive) {
                try {
                    healthChecker.check()
                    consecutiveFailures = 0
                    _state.update {
                        if (it is DashboardUiState.Ready) it.copy(
                            health = HealthSnapshot(
                                lastOkMs = System.currentTimeMillis(),
                                lastError = null,
                                isStale = false,
                            ),
                        ) else it
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    consecutiveFailures++
                    val nowMs = System.currentTimeMillis()
                    _state.update {
                        if (it is DashboardUiState.Ready) it.copy(
                            health = HealthSnapshot(
                                lastOkMs = it.health.lastOkMs,
                                lastError = e.toUserMessage(),
                                isStale = it.health.lastOkMs != null &&
                                    (nowMs - it.health.lastOkMs > HealthSnapshot.STALE_THRESHOLD_MS),
                            ),
                        ) else it
                    }
                    if (consecutiveFailures >= DEGRADED_FAILURE_THRESHOLD) {
                        _state.value = DashboardUiState.Degraded(e.toUserMessage())
                        break  // degraded break exits without waiting for the next delay
                    }
                }
                // delay comes after success or sub-threshold failure; degraded break skips it
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    internal companion object {
        const val POLL_INTERVAL_MS = 30_000L
        const val DEGRADED_FAILURE_THRESHOLD = 2
    }
}
