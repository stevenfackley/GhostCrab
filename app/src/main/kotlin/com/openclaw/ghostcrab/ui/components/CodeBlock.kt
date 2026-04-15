package com.openclaw.ghostcrab.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.MonoFontFamily
import com.openclaw.ghostcrab.ui.theme.Spacing

/**
 * Displays a monospace code string inside a glass-style container with a copy button.
 *
 * @param code The string to display and copy.
 * @param modifier Applied to the outer container.
 * @param onCopied Called immediately after the text is placed on the clipboard.
 */
@Composable
public fun CodeBlock(
    code: String,
    modifier: Modifier = Modifier,
    onCopied: () -> Unit = {},
) {
    val clipboardManager = LocalClipboardManager.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(BrandTokens.colorAbyss)
            .border(1.dp, BrandTokens.colorOutline, MaterialTheme.shapes.medium)
            .background(BrandTokens.colorGlass)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm)
            .semantics { contentDescription = "Code: $code" },
    ) {
        Text(
            text = code,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
            color = BrandTokens.colorCyanPulse,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(end = 40.dp),
        )
        IconButton(
            onClick = {
                clipboardManager.setText(AnnotatedString(code))
                onCopied()
            },
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = "Copy",
                tint = BrandTokens.colorTextSecondary,
            )
        }
    }
}
