package com.openclaw.ghostcrab.ui.installedskills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openclaw.ghostcrab.domain.repository.InstalledSkillRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for [InstalledSkillsScreen].
 *
 * Observes the hot [InstalledSkillRepository.observeInstalled] flow and triggers a one-shot
 * [InstalledSkillRepository.refreshFromGateway] on init so the list reflects live gateway state.
 *
 * @param repo Frozen v1.1 skill repository — only registered in Koin when
 *   `BuildConfig.SKILLS_INSTALL_ENABLED` is true.
 */
class InstalledSkillsViewModel(
    private val repo: InstalledSkillRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<InstalledSkillsUiState>(InstalledSkillsUiState.Loading)

    /** Observable UI state. */
    val state: StateFlow<InstalledSkillsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repo.observeInstalled().collect { list ->
                _state.update { current ->
                    when (current) {
                        is InstalledSkillsUiState.Loading -> InstalledSkillsUiState.Ready(skills = list)
                        is InstalledSkillsUiState.Ready -> current.copy(skills = list)
                    }
                }
            }
        }
        refresh()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Triggers a one-shot refresh from `skills.list`. Sets
     * [InstalledSkillsUiState.Ready.errorMessage] on failure.
     */
    fun refresh() {
        viewModelScope.launch {
            withReady { it.copy(isRefreshing = true) }
            val result = repo.refreshFromGateway()
            result.onSuccess {
                withReady { s -> s.copy(isRefreshing = false) }
            }.onFailure { e ->
                val current = _state.value
                if (current is InstalledSkillsUiState.Loading) {
                    // Failed to load with no cached list — surface empty + error
                    _state.value = InstalledSkillsUiState.Ready(
                        skills = emptyList(),
                        errorMessage = e.message ?: "unknown",
                    )
                } else {
                    withReady { s -> s.copy(isRefreshing = false, errorMessage = e.message) }
                }
            }
        }
    }

    /** Opens the uninstall confirmation dialog for [slug]. */
    fun requestUninstall(slug: String) {
        withReady { it.copy(pendingUninstallSlug = slug) }
    }

    /** Dismisses the uninstall confirmation dialog without acting. */
    fun cancelUninstall() {
        withReady { it.copy(pendingUninstallSlug = null) }
    }

    /**
     * Confirms uninstall of the skill currently pending. Closes the dialog, emits
     * an in-flight indicator, and sets [InstalledSkillsUiState.Ready.uninstallSuccessSlug]
     * on success or [InstalledSkillsUiState.Ready.errorMessage] on failure.
     */
    fun confirmUninstall() {
        val slug = (_state.value as? InstalledSkillsUiState.Ready)?.pendingUninstallSlug ?: return
        withReady { it.copy(pendingUninstallSlug = null, uninstallingSlug = slug) }
        viewModelScope.launch {
            repo.uninstall(slug)
                .onSuccess {
                    withReady { s -> s.copy(uninstallingSlug = null, uninstallSuccessSlug = slug) }
                }
                .onFailure { e ->
                    withReady { s -> s.copy(uninstallingSlug = null, errorMessage = e.message) }
                }
        }
    }

    /** Clears the uninstall-success flag after the snackbar has been shown. */
    fun clearUninstallSuccess() {
        withReady { it.copy(uninstallSuccessSlug = null) }
    }

    /** Clears the transient error message after the snackbar has been shown. */
    fun clearError() {
        withReady { it.copy(errorMessage = null) }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun withReady(block: (InstalledSkillsUiState.Ready) -> InstalledSkillsUiState) {
        _state.update { if (it is InstalledSkillsUiState.Ready) block(it) else it }
    }
}
