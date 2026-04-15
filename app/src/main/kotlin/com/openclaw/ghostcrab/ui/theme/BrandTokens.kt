package com.openclaw.ghostcrab.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Single source of truth for all GhostCrab brand colors and surface rules.
 *
 * All other theme files consume these tokens. No hardcoded hex values elsewhere.
 */
object BrandTokens {

    // ── Backgrounds ──────────────────────────────────────────────────────────
    /** Primary background — deep abyss dark. */
    val colorAbyss = Color(0xFF0F1115)

    /** Raised surface base — slightly lighter than abyss. */
    val colorAbyssRaised = Color(0xFF16191F)

    /** Glass surface overlay — 6% white-alpha over abyss. */
    val colorGlass = Color(0x0FFFFFFF)

    // ── Brand / Status ────────────────────────────────────────────────────────
    /** Primary actions, connected state — luminous cyan. */
    val colorCyanPrimary = Color(0xFF5BE9FF)

    /** Scanning / mDNS pulse — pale azure. */
    val colorCyanPulse = Color(0xFF7BD8FF)

    /** Disconnected / unreachable / HTTP-mode banner — muted amber. */
    val colorAmberWarn = Color(0xFFE0A458)

    /** Auth / validation errors — high-contrast crimson. */
    val colorCrimsonError = Color(0xFFFF4D6D)

    // ── Text ──────────────────────────────────────────────────────────────────
    /** Primary text on dark backgrounds. */
    val colorTextPrimary = Color(0xFFE8EAED)

    /** Secondary / subdued text. */
    val colorTextSecondary = Color(0xFF8B949E)

    /** Disabled / placeholder text. */
    val colorTextDisabled = Color(0xFF484F58)

    // ── Surfaces ──────────────────────────────────────────────────────────────
    /** Glass surface alpha — use with [colorGlass] for the glassmorphism effect. */
    const val glassSurfaceAlpha = 0.06f

    /** Outline / divider on dark surfaces. */
    val colorOutline = Color(0xFF30363D)
}
