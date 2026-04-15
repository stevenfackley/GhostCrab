package com.openclaw.ghostcrab.ui.navigation

/**
 * Type-safe navigation destinations for GhostCrab.
 *
 * All destinations are `data object` or `data class` per Compose Navigation 2.8+ type-safe API.
 */
sealed interface Routes {

    // ── Onboarding (Phase 5) ──────────────────────────────────────────────────
    data object Onboarding : Routes

    // ── Connection ────────────────────────────────────────────────────────────
    /** List of saved profiles + entry points for manual entry and LAN scan. */
    data object ConnectionPicker : Routes

    /**
     * Manual URL + token entry form.
     *
     * @param onboardingMode When `true`, successful connect returns to onboarding step 7
     *   instead of routing to Dashboard.
     */
    data class ManualEntry(val onboardingMode: Boolean = false) : Routes

    /**
     * LAN discovery scan screen.
     *
     * @param onboardingMode When `true`, successful selection returns to onboarding step 7.
     */
    data class Scan(val onboardingMode: Boolean = false) : Routes

    // ── Main App ──────────────────────────────────────────────────────────────
    data object Dashboard : Routes
    data object ConfigEditor : Routes
    data object ModelManager : Routes
    data object AIRecommendation : Routes
    data object Settings : Routes
}
