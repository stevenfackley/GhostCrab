package com.openclaw.ghostcrab.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.MonoFontFamily
import com.openclaw.ghostcrab.ui.theme.Spacing

/**
 * A labeled integer input field rendered inside a [GlassSurface].
 *
 * Uses JetBrains Mono (monospace) and a numeric keyboard. Validates the entered value
 * against [min]..[max] on every keystroke; calls [onError] with a range message on
 * violation and `null` when valid.
 *
 * @param label Field label.
 * @param value Current integer value.
 * @param onValueChange Called with the new valid integer value.
 * @param onError Called with an error string on range violation, or `null` when valid.
 * @param min Minimum inclusive value (default [Int.MIN_VALUE]).
 * @param max Maximum inclusive value (default [Int.MAX_VALUE]).
 * @param modifier Applied to the outer [GlassSurface].
 */
@Composable
fun IntFieldRow(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    onError: (String?) -> Unit,
    min: Int = Int.MIN_VALUE,
    max: Int = Int.MAX_VALUE,
    modifier: Modifier = Modifier,
) {
    var rawText by remember(value) { mutableStateOf(value.toString()) }
    var errorText by remember { mutableStateOf<String?>(null) }

    GlassSurface(modifier = modifier.fillMaxWidth(), contentPadding = Spacing.sm) {
        Column(modifier = Modifier.padding(horizontal = Spacing.xs)) {
            OutlinedTextField(
                value = rawText,
                onValueChange = { text ->
                    rawText = text
                    val parsed = text.toIntOrNull()
                    when {
                        parsed == null -> {
                            val err = "Must be $min\u2013$max"
                            errorText = err
                            onError(err)
                        }
                        parsed < min || parsed > max -> {
                            val err = "Must be $min\u2013$max"
                            errorText = err
                            onError(err)
                        }
                        else -> {
                            errorText = null
                            onError(null)
                            onValueChange(parsed)
                        }
                    }
                },
                label = { Text(label) },
                isError = errorText != null,
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = MonoFontFamily),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (errorText != null) {
                Text(
                    text = errorText!!,
                    color = BrandTokens.colorCrimsonError,
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = Spacing.xs, top = Spacing.xs),
                )
            }
        }
    }
}
