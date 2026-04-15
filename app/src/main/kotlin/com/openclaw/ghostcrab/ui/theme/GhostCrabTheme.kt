package com.openclaw.ghostcrab.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * Root theme composable. Wraps Material3 with GhostCrab's design system.
 *
 * Always applies the dark color scheme (v1.0 is dark-mode only).
 */
@Composable
fun GhostCrabTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GhostCrabDarkColorScheme,
        typography = GhostCrabTypography,
        shapes = GhostCrabShapes,
        content = content,
    )
}
