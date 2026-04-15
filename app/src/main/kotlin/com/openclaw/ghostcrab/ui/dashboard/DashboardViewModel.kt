package com.openclaw.ghostcrab.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openclaw.ghostcrab.data.api.dto.HealthResponse
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import com.openclaw.ghostcrab.domain.repository.GatewayConnectionManager
import com.openclaw.ghostcrab.domain.repository.ModelRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * @param healthChecker Performs a `/health` call against the given URL. Injected for testability.
 */
class DashboardViewModel(
    private val connectionManager: GatewayConnectionManager,
    private val modelRepository: ModelRepository,
    private val healthChecker: suspend (url: String) -> HealthResponse,
) : ViewModel() {

    private val _state = MutableStateFlow<DashboardUiState>(DashboardUiState.Loading)
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    private var pollJob: Job? = null

    init {
        viewModelScope.launch {
            connectionManager.connectionState.collect { conn ->
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
            val models = try { modelRepository.getModels() } catch (_: Exception) { emptyList() }

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
                    healthChecker(conn.url)
                    consecutiveFailures = 0
                    (_state.value as? DashboardUiState.Ready)?.let { current ->
                        _state.value = current.copy(
                            health = HealthSnapshot(lastOkMs = System.currentTimeMillis(), lastError = null),
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    consecutiveFailures++
                    (_state.value as? DashboardUiState.Ready)?.let { current ->
                        _state.value = current.copy(
                            health = HealthSnapshot(
                                lastOkMs = current.health.lastOkMs,
                                lastError = e.message,
                            ),
                        )
                    }
                    if (consecutiveFailures >= DEGRADED_FAILURE_THRESHOLD) {
                        _state.value = DashboardUiState.Degraded(e.message ?: "Health check failed")
                        break
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    internal companion object {
        const val POLL_INTERVAL_MS = 30_000L
        const val DEGRADED_FAILURE_THRESHOLD = 2
    }
}
