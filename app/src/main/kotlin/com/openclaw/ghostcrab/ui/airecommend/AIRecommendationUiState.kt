package com.openclaw.ghostcrab.ui.airecommend

import com.openclaw.ghostcrab.domain.model.AIRecommendation
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
     * Gateway doesn't expose the AI-recommend skill. [missingScope] is non-null when
     * we know the user's token lacks the scope needed to install it — lets the UI
     * show scope-specific copy instead of the generic "install via CLI" card.
     */
    data class SkillUnavailable(val missingScope: String? = null) : AIRecommendationUiState

    /**
     * Recommendation received and ready to display.
     *
     * @param query The query that produced this result (shown for context).
     * @param recommendation Full recommendation from the gateway AI skill.
     * @param selectedChanges Subset of [AIRecommendation.suggestedChanges] the user wants to apply.
     *   Defaults to all suggestions selected. **Always pass this explicitly when calling [copy] with
     *   a new [recommendation]** — the default re-evaluates against the new recommendation, so
     *   stale selections from the old recommendation are silently discarded if omitted.
     * @param isApplying `true` while a config-apply operation is in flight.
     * @param applySuccess `true` after all selected changes were applied successfully.
     *   Cleared by [AIRecommendationViewModel.clearApplySuccess].
     * @param applyError Non-null when a config-apply operation failed.
     *   Cleared by [AIRecommendationViewModel.clearApplyError].
     */
    data class Ready(
        val query: String,
        val recommendation: AIRecommendation,
        val selectedChanges: Set<SuggestedChange> = recommendation.suggestedChanges.toSet(),
        val isApplying: Boolean = false,
        val applySuccess: Boolean = false,
        val applyError: String? = null,
    ) : AIRecommendationUiState

    /**
     * An unrecoverable error occurred.
     *
     * @param message Human-readable error — includes URL, HTTP status, and exception class per brand rules.
     */
    data class Error(val message: String) : AIRecommendationUiState
}
