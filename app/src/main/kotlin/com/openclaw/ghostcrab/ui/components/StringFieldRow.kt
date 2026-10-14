package com.openclaw.ghostcrab.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.openclaw.ghostcrab.R
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.MonoFontFamily
import com.openclaw.ghostcrab.ui.theme.Spacing

/**
 * A labeled string input field rendered inside a [GlassSurface].
 *
 * @param label Field label shown as the floating label.
 * @param value Current string value.
 * @param onValueChange Called on every keystroke with the new text.
 * @param modifier Applied to the outer [GlassSurface].
 * @param mono When `true`, renders the field text in JetBrains Mono.
 * @param singleLine When `true` (default), constrains to a single line.
 * @param error When non-null, highlights the field in error state and shows the message below.
 * @param secret When true, masks the value and shows a reveal toggle (tokens, keys).
 */
@Suppress("LongParameterList")
@Composable
fun StringFieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    singleLine: Boolean = true,
    error: String? = null,
    secret: Boolean = false,
) {
    var revealed by remember { mutableStateOf(false) }
    GlassSurface(modifier = modifier.fillMaxWidth(), contentPadding = Spacing.sm) {
        Column(modifier = Modifier.padding(horizontal = Spacing.xs)) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(label) },
                isError = error != null,
                textStyle = if (mono) {
                    TextStyle(fontFamily = MonoFontFamily)
                } else {
                    TextStyle.Default
                },
                singleLine = singleLine,
                visualTransformation = if (secret && !revealed) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                trailingIcon = if (secret) {
                    { SecretVisibilityToggle(revealed = revealed, onToggle = { revealed = !revealed }) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (error != null) {
                Text(
                    text = error,
                    color = BrandTokens.colorCrimsonError,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = Spacing.xs, top = Spacing.xs),
                )
            }
        }
    }
}

@Composable
private fun SecretVisibilityToggle(revealed: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            imageVector = if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
            contentDescription = stringResource(
                if (revealed) R.string.field_secret_hide else R.string.field_secret_show,
            ),
        )
    }
}
