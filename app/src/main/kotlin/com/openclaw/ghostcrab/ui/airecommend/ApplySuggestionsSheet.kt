package com.openclaw.ghostcrab.ui.airecommend

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.openclaw.ghostcrab.R
import com.openclaw.ghostcrab.domain.model.SuggestedChange
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.MonoFontFamily
import com.openclaw.ghostcrab.ui.theme.Spacing

/**
 * Modal bottom sheet presenting the list of AI-suggested config changes.
 *
 * The user can toggle individual changes on/off before tapping Apply.
 * Routing to [ConfigRepository] happens in [AIRecommendationViewModel.applySelectedChanges].
 *
 * @param changes All suggested changes from the AI response.
 * @param selectedChanges Set of changes the user has toggled on.
 * @param isApplying `true` while the apply coroutine is running — disables the button.
 * @param onToggle Called when the user taps a change row's checkbox.
 * @param onApply Called when the user taps "Apply N Selected".
 * @param onDismiss Called when the sheet is dismissed without applying.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApplySuggestionsSheet(
    changes: List<SuggestedChange>,
    selectedChanges: Set<SuggestedChange>,
    isApplying: Boolean,
    onToggle: (SuggestedChange) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BrandTokens.colorAbyssRaised,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(Spacing.md),
        ) {
            // Header
            Text(
                text = stringResource(R.string.ai_sheet_title),
                style = MaterialTheme.typography.titleMedium.copy(
                    color = BrandTokens.colorTextPrimary,
                    fontWeight = FontWeight.Bold,
                ),
            )

            Spacer(Modifier.height(Spacing.xs))

            Text(
                text = stringResource(R.string.ai_sheet_subtitle, changes.size),
                style = MaterialTheme.typography.bodySmall.copy(
                    color = BrandTokens.colorTextSecondary,
                ),
            )

            Spacer(Modifier.height(Spacing.md))
            HorizontalDivider(color = BrandTokens.colorOutline)
            Spacer(Modifier.height(Spacing.sm))

            // Change list — constrained height so the Apply button stays visible
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                // Key includes index to handle duplicate section.key combinations from the AI
                itemsIndexed(changes, key = { index, change -> "$index.${change.section}.${change.key}" }) { _, change ->
                    SuggestionRow(
                        change = change,
                        isSelected = change in selectedChanges,
                        onToggle = { onToggle(change) },
                    )
                    HorizontalDivider(
                        color = BrandTokens.colorOutline.copy(alpha = 0.5f),
                        modifier = Modifier.padding(top = Spacing.sm),
                    )
                }
            }

            Spacer(Modifier.height(Spacing.md))

            // Apply button
            val count = selectedChanges.size
            Button(
                onClick = onApply,
                enabled = count > 0 && !isApplying,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandTokens.colorCyanPrimary,
                    contentColor = BrandTokens.colorAbyss,
                    disabledContainerColor = BrandTokens.colorCyanPrimary.copy(alpha = 0.35f),
                    disabledContentColor = BrandTokens.colorAbyss.copy(alpha = 0.5f),
                ),
            ) {
                Text(
                    text = if (count > 0) {
                        stringResource(R.string.ai_sheet_apply_button, count)
                    } else {
                        stringResource(R.string.ai_sheet_apply_none)
                    },
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

// ── Suggestion row ────────────────────────────────────────────────────────────

@Composable
private fun SuggestionRow(
    change: SuggestedChange,
    isSelected: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = BrandTokens.colorCyanPrimary,
                uncheckedColor = BrandTokens.colorTextSecondary,
                checkmarkColor = BrandTokens.colorAbyss,
            ),
        )

        Spacer(Modifier.width(Spacing.xs))

        Column(modifier = Modifier.weight(1f)) {
            // Section.key in mono
            Text(
                text = "${change.section}.${change.key}",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = MonoFontFamily,
                    color = BrandTokens.colorCyanPrimary,
                    fontWeight = FontWeight.Bold,
                ),
            )

            Spacer(Modifier.height(Spacing.xs))

            // Before → After
            if (change.currentValue != null) {
                Text(
                    text = stringResource(R.string.ai_sheet_before_label) + "  " + change.currentValue,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = MonoFontFamily,
                        color = BrandTokens.colorTextSecondary,
                        textDecoration = TextDecoration.LineThrough,
                    ),
                )
                Spacer(Modifier.height(Spacing.xxs))
            }

            Text(
                text = stringResource(R.string.ai_sheet_after_label) + "   " + change.suggestedValue,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = MonoFontFamily,
                    color = BrandTokens.colorCyanPulse,
                ),
            )

            // Rationale
            if (change.rationale.isNotBlank()) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = change.rationale,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = BrandTokens.colorTextSecondary,
                    ),
                )
            }
        }
    }
}
