package com.openclaw.ghostcrab.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.Spacing

/**
 * Glassmorphism surface: 6% white-alpha overlay over [BrandTokens.colorAbyss] with a
 * subtle outline border. Use instead of solid gray cards per brand rules.
 *
 * @param modifier Applied to the outer [Box].
 * @param contentPadding Inner padding for the content slot. Defaults to [Spacing.md].
 * @param content Slot composable rendered inside the glass surface.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    contentPadding: Dp = Spacing.md,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(BrandTokens.colorGlass)
            .border(
                width = 1.dp,
                color = BrandTokens.colorOutline,
                shape = MaterialTheme.shapes.medium,
            )
            .padding(contentPadding),
        content = content,
    )
}
