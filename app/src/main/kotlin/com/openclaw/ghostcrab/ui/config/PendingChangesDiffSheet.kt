package com.openclaw.ghostcrab.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.MonoFontFamily
import com.openclaw.ghostcrab.ui.theme.Spacing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private val prettyJson = Json { prettyPrint = true }

/**
 * A [ModalBottomSheet] that shows a before/after diff for a pending config section change.
 *
 * Displays the section name as a title, then "Before" (amber) and "After" (cyan) blocks
 * in JetBrains Mono. Offers "Save" (cyan) and "Cancel" (outline) buttons.
 *
 * @param sectionKey Top-level section key shown as the sheet title.
 * @param oldValue The current server-side value.
 * @param newValue The user's proposed new value.
 * @param onConfirm Called when the user taps "Save".
 * @param onDismiss Called when the user taps "Cancel" or dismisses the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingChangesDiffSheet(
    sectionKey: String,
    oldValue: JsonElement,
    newValue: JsonElement,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val oldText = prettyJson.encodeToString(JsonElement.serializer(), oldValue)
    val newText = prettyJson.encodeToString(JsonElement.serializer(), newValue)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md)
                .padding(bottom = Spacing.lg)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "Review changes: $sectionKey",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(modifier = Modifier.height(Spacing.md))

            // Before block
            Text(
                text = "Before",
                style = MaterialTheme.typography.labelMedium,
                color = BrandTokens.colorAmberWarn,
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = oldText,
                style = TextStyle(fontFamily = MonoFontFamily),
                color = BrandTokens.colorAmberWarn,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(Spacing.md))

            // After block
            Text(
                text = "After",
                style = MaterialTheme.typography.labelMedium,
                color = BrandTokens.colorCyanPrimary,
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = newText,
                style = TextStyle(fontFamily = MonoFontFamily),
                color = BrandTokens.colorCyanPrimary,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(Spacing.lg))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrandTokens.colorCyanPrimary,
                        contentColor = BrandTokens.colorAbyss,
                    ),
                ) {
                    Text("Save")
                }
            }
        }
    }
}
