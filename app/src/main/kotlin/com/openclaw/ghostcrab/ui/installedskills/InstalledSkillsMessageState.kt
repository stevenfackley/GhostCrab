package com.openclaw.ghostcrab.ui.installedskills

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.Spacing

/**
 * The Installed Skills screen's full-screen message (disconnected, load failed): a centered title, an optional body,
 * an optional detail line, and one action.
 *
 * @param title Headline.
 * @param titleColor Headline color, a [BrandTokens] color.
 * @param body Explanation, or `null` for none.
 * @param detail The failure as `toUserMessage()` renders it, or `null` for none.
 * @param actionLabel The button's label.
 * @param onAction Called when the button is tapped.
 * @param modifier Applied to the full-size container.
 */
@Composable
internal fun InstalledSkillsMessageState(
    title: String,
    titleColor: Color,
    body: String?,
    detail: String?,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(Spacing.xl),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(color = titleColor, fontWeight = FontWeight.Bold),
            )
            listOfNotNull(body, detail).forEach { line ->
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall.copy(color = BrandTokens.colorTextSecondary),
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(Spacing.md))
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandTokens.colorCyanPrimary,
                    contentColor = BrandTokens.colorAbyss,
                ),
            ) {
                Text(text = actionLabel, fontWeight = FontWeight.Bold)
            }
        }
    }
}
