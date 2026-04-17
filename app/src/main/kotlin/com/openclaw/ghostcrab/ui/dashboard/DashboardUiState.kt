package com.openclaw.ghostcrab.ui.dashboard

import com.openclaw.ghostcrab.domain.model.GatewayConnection
import com.openclaw.ghostcrab.domain.model.ModelInfo

/**
 * Point-in-time snapshot of the most recent `/health` poll result.
 *
 * @param lastOkMs Epoch millis of the last successful poll, or null if never polled.
 * @param lastError Error message from the last failed poll, or null if the last poll succeeded.
 * @param isStale True when the last successful health check was more than 60 s ago.
 *   Computed by DashboardViewModel at emission time so Compose structural equality stays stable.
 */
data class HealthSnapshot(
    val lastOkMs: Long?,
    val lastError: String?,
    val isStale: Boolean = false,
) {
    companion object {
        // Should remain > 1.5 × DashboardViewModel.POLL_INTERVAL_MS (currently 30 s → threshold 60 s)
        const val STALE_THRESHOLD_MS = 60_000L
    }
}

/** Single observable state for [DashboardViewModel]. */
sealed interface DashboardUiState {

    /** Fetching initial models; not yet ready to render. */
    data object Loading : DashboardUiState

    /**
     * Dashboard is operational.
     *
     * @param connection Live connection details from [GatewayConnectionManager].
     * @param health Most recent health poll snapshot.
     * @param models Model list from the last successful fetch.
     */
    data class Ready(
        val connection: GatewayConnection.Connected,
        val health: HealthSnapshot,
        val models: List<ModelInfo>,
    ) : DashboardUiState

    /**
     * Gateway is unreachable (two consecutive `/health` failures).
     *
     * @param reason Last error message from the health poller.
     */
    data class Degraded(val reason: String) : DashboardUiState

    /** Connection has been terminated; UI should navigate back. */
    data object Disconnected : DashboardUiState
}
