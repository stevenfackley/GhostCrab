package com.openclaw.ghostcrab.ui.components

import android.content.ClipData
import android.content.ClipDescription
import android.os.Build
import android.os.PersistableBundle
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.MonoFontFamily
import com.openclaw.ghostcrab.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * Displays a monospace code string inside a glass-style container with a copy button.
 *
 * @param code The string to display and copy.
 * @param modifier Applied to the outer container.
 * @param sensitive Mark the clip as sensitive (honoured on API 33+): the system clipboard
 *   preview hides the text and clipboard-sync features skip it. Use for tokens and secrets.
 * @param onCopied Called immediately after the text is placed on the clipboard.
 */
@Composable
public fun CodeBlock(
    code: String,
    modifier: Modifier = Modifier,
    sensitive: Boolean = false,
    onCopied: () -> Unit = {},
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

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
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(buildClip(code, sensitive)))
                    onCopied()
                }
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

/**
 * Builds a plain-text clip, flagged [ClipDescription.EXTRA_IS_SENSITIVE] when [sensitive]
 * so Android 13+ suppresses the on-screen clipboard preview for it.
 */
private fun buildClip(text: String, sensitive: Boolean): ClipData {
    val clip = ClipData.newPlainText(if (sensitive) "secret" else "code", text)
    if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    return clip
}
