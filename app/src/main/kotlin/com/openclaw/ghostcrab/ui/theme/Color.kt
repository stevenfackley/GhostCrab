package com.openclaw.ghostcrab.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme

/**
 * Material3 color schemes built entirely from [BrandTokens].
 *
 * Dark scheme is the default (GhostCrab is dark-mode only for v1.0).
 * Light scheme mirrors the dark palette with slightly adjusted surfaces — kept here
 * for completeness; it is not exposed in [GhostCrabTheme] until v2.0.
 */
internal val GhostCrabDarkColorScheme = darkColorScheme(
    primary = BrandTokens.colorCyanPrimary,
    onPrimary = BrandTokens.colorAbyss,
    primaryContainer = BrandTokens.colorAbyssRaised,
    onPrimaryContainer = BrandTokens.colorCyanPrimary,
    secondary = BrandTokens.colorCyanPulse,
    onSecondary = BrandTokens.colorAbyss,
    secondaryContainer = BrandTokens.colorAbyssRaised,
    onSecondaryContainer = BrandTokens.colorCyanPulse,
    tertiary = BrandTokens.colorAmberWarn,
    onTertiary = BrandTokens.colorAbyss,
    error = BrandTokens.colorCrimsonError,
    onError = BrandTokens.colorAbyss,
    errorContainer = BrandTokens.colorAbyssRaised,
    onErrorContainer = BrandTokens.colorCrimsonError,
    background = BrandTokens.colorAbyss,
    onBackground = BrandTokens.colorTextPrimary,
    surface = BrandTokens.colorAbyssRaised,
    onSurface = BrandTokens.colorTextPrimary,
    surfaceVariant = BrandTokens.colorAbyssRaised,
    onSurfaceVariant = BrandTokens.colorTextSecondary,
    outline = BrandTokens.colorOutline,
    outlineVariant = BrandTokens.colorOutline,
    inverseSurface = BrandTokens.colorTextPrimary,
    inverseOnSurface = BrandTokens.colorAbyss,
    inversePrimary = BrandTokens.colorCyanPrimary,
)

/** Light scheme mirrors dark — not used in v1.0. Kept for completeness. */
internal val GhostCrabLightColorScheme = lightColorScheme(
    primary = BrandTokens.colorCyanPrimary,
    onPrimary = BrandTokens.colorAbyss,
    secondary = BrandTokens.colorCyanPulse,
    onSecondary = BrandTokens.colorAbyss,
    error = BrandTokens.colorCrimsonError,
    background = BrandTokens.colorAbyss,
    onBackground = BrandTokens.colorTextPrimary,
    surface = BrandTokens.colorAbyssRaised,
    onSurface = BrandTokens.colorTextPrimary,
)
