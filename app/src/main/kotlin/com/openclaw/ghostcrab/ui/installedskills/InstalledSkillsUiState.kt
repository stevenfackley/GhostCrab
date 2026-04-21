package com.openclaw.ghostcrab.ui.installedskills

import com.openclaw.ghostcrab.domain.model.InstalledSkill

/**
 * Exhaustive UI state for [InstalledSkillsScreen].
 */
sealed interface InstalledSkillsUiState {

    /** First load in flight — no cached list yet. */
    data object Loading : InstalledSkillsUiState

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
