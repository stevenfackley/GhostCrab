package com.openclaw.ghostcrab.ui.airecommend

import com.openclaw.ghostcrab.domain.model.AIRecommendation
import com.openclaw.ghostcrab.domain.model.SkillInstallError
import com.openclaw.ghostcrab.domain.model.SkillInstallProgress
import com.openclaw.ghostcrab.domain.model.SuggestedChange

/**
 * Exhaustive UI state for [AIRecommendationScreen].
 */
sealed interface AIRecommendationUiState {

    /** Initial state — query field visible, no result yet. */
    data object Idle : AIRecommendationUiState

    /** Request in flight — show spinner. */
    data object Loading : AIRecommendationUiState

    /**
     * Gateway doesn't expose the AI-recommend skill.
     *
     * @param missingScope Non-null when the user's token lacks the scope needed to install
     *   the skill. UI shows scope-specific copy and hides the Install button.
     * @param canInstall `true` when the in-app install path is wired (flag on + repo present)
     *   and no scope is known to block install. Drives Install button visibility.
     * @param installProgress Non-null while an install is in flight. Terminal progress is
     *   folded back into state transitions (Idle on success, installError on failure) —
     *   never retained here after completion.
     * @param installError Non-null after an install has terminally failed. Cleared when
     *   the user retries or dismisses.
     */
    data class SkillUnavailable(
        val missingScope: String? = null,
        val canInstall: Boolean = false,
        val installProgress: SkillInstallProgress? = null,
        val installError: SkillInstallError? = null,
    ) : AIRecommendationUiState

    /**
     * Recommendation received and ready to display.
     *
     * @param query The query that produced this result (shown for context).
     * @param recommendation Full recommendation from the gateway AI skill.
     * @param selectedChanges Changes the user ticked. Starts **empty** — nothing is pre-selected.
     * @param isApplying `true` while a config-apply operation is in flight.
     * @param applySuccess `true` after all selected changes were applied successfully.
     *   Cleared by [AIRecommendationViewModel.clearApplyFlags].
     * @param applyError Non-null when a config-apply operation failed.
     *   Cleared by [AIRecommendationViewModel.clearApplyFlags].
     * @param confirmSecurityChanges Non-empty while the UI must ask the user to confirm these
     *   security-affecting changes before anything is written.
     * @param droppedSuggestionCount How many AI suggestions were removed from [recommendation]
     *   because they echoed a redacted value (see [isApplicable]); the sheet tells the user.
     */
    data class Ready(
        val query: String,
        val recommendation: AIRecommendation,
        val selectedChanges: Set<SuggestedChange> = emptySet(),
        val isApplying: Boolean = false,
        val applySuccess: Boolean = false,
        val applyError: String? = null,
        val confirmSecurityChanges: List<SuggestedChange> = emptyList(),
        val droppedSuggestionCount: Int = 0,
    ) : AIRecommendationUiState

    /**
     * An unrecoverable error occurred.
     *
     * @param message Human-readable error — includes URL, HTTP status, and exception class per brand rules.
     */
    data class Error(val message: String) : AIRecommendationUiState
}
