package com.openclaw.ghostcrab.ui.dashboard

import com.openclaw.ghostcrab.domain.model.GatewayConnection
import com.openclaw.ghostcrab.domain.model.ModelInfo

/**
 * Point-in-time snapshot of the most recent `/health` poll result.
 *
 * @param lastOkMs Epoch millis of the last successful poll, or null if never polled.
 * @param lastError Error message from the last failed poll, or null if the last poll succeeded.
 */
data class HealthSnapshot(
    val lastOkMs: Long?,
    val lastError: String?,
) {
    /**
     * True when the last successful health check was more than 60 s ago.
     * Drives the amber "stale" status dot in the UI.
     */
    val isStale: Boolean
        get() = lastOkMs != null && (System.currentTimeMillis() - lastOkMs > STALE_THRESHOLD_MS)

    companion object {
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
