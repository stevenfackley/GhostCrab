package com.openclaw.ghostcrab.ui.installedskills

import com.openclaw.ghostcrab.domain.model.InstalledSkill

/**
 * Exhaustive UI state for [InstalledSkillsScreen].
 *
 * Driven by the connection state first: a list or a read result counts only while a session is live. "No skills
 * installed" is [Ready] with an empty list, which only a live session's `skills.status` answer produces.
 */
sealed interface InstalledSkillsUiState {

    /**
     * Nothing to show yet, and something is in flight that ends: the first `skills.status` read of the live session,
     * or the connect / silent reconnect it waits for (that one ends in a new session, which reads, or in
     * [Disconnected]).
     */
    data object Loading : InstalledSkillsUiState

    /**
     * No live connection: never connected, disconnected, or the connect or silent reconnect failed. No rows are
     * shown: they belong to a session that is gone.
     *
     * @param reason Why the connection ended (`toUserMessage()` of the failure), or `null` when it was not a
     *   failure (never connected, or a disconnect).
     */
    data class Disconnected(val reason: String?) : InstalledSkillsUiState

    /**
     * The live session's first read failed, so there is no list to show (not even an empty one).
     *
     * @param message The failure, as `toUserMessage()` renders it.
     */
    data class LoadFailed(val message: String) : InstalledSkillsUiState

    /**
     * Loaded.
     *
     * @param skills Current list of installed skills from the gateway.
     * @param isRefreshing True while a background refresh is in flight (pull-to-refresh).
     * @param pendingUninstallSlug Slug currently awaiting uninstall confirmation; null = no dialog.
     * @param uninstallingSlug Slug for which an uninstall RPC is in flight; null = idle.
     * @param errorMessage Transient snackbar message. Cleared by [InstalledSkillsViewModel.clearError].
     * @param uninstallSuccessSlug Transient snackbar message slug. Cleared by
     *   [InstalledSkillsViewModel.clearUninstallSuccess].
     */
    data class Ready(
        val skills: List<InstalledSkill>,
        val isRefreshing: Boolean = false,
        val pendingUninstallSlug: String? = null,
        val uninstallingSlug: String? = null,
        val errorMessage: String? = null,
        val uninstallSuccessSlug: String? = null,
    ) : InstalledSkillsUiState
}
