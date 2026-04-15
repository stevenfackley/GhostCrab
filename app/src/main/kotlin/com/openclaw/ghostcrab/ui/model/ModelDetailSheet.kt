package com.openclaw.ghostcrab.ui.model

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openclaw.ghostcrab.R
import com.openclaw.ghostcrab.domain.model.ModelInfo
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.Spacing
import com.openclaw.ghostcrab.ui.theme.MonoFontFamily

/**
 * Modal bottom sheet showing full detail for a single [ModelInfo].
 *
 * Shows provider, status (color-coded), capabilities, and a "Set Active" button
 * when the model is not already active.
 *
 * @param model The model to display.
 * @param onSetActive Called when user taps "Set Active". Caller dismisses the sheet.
 * @param onDismiss Called when the sheet is dismissed (swipe or back).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
public fun ModelDetailSheet(
    model: ModelInfo,
    onSetActive: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BrandTokens.colorAbyssRaised,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        ) {
            // Title
            Text(
                text = model.displayName,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = BrandTokens.colorTextPrimary,
                ),
            )
            Spacer(modifier = Modifier.height(Spacing.xs))

            // Model ID in mono
            Text(
                text = model.id,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = MonoFontFamily,
                    color = BrandTokens.colorTextSecondary,
                ),
            )

            Spacer(modifier = Modifier.height(Spacing.md))

            // Provider row
            DetailRow(
                label = stringResource(R.string.model_detail_provider),
                value = model.provider,
                mono = true,
            )

            Spacer(modifier = Modifier.height(Spacing.sm))

            // Status row — color-coded
            val statusColor = when (model.status) {
                "auth-error" -> BrandTokens.colorCrimsonError
                "loading" -> BrandTokens.colorAmberWarn
                "ready" -> BrandTokens.colorCyanPrimary
                else -> BrandTokens.colorTextSecondary
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.model_detail_status),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = BrandTokens.colorTextSecondary,
                    ),
                )
                Text(
                    text = model.status,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = MonoFontFamily,
                        color = statusColor,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }

            Spacer(modifier = Modifier.height(Spacing.md))

            // Capabilities
            Text(
                text = stringResource(R.string.model_detail_capabilities),
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = BrandTokens.colorTextSecondary,
                ),
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            if (model.capabilities.isEmpty()) {
                Text(
                    text = stringResource(R.string.model_detail_no_capabilities),
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = BrandTokens.colorTextDisabled,
                    ),
                )
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    model.capabilities.forEach { cap ->
                        SuggestionChip(
                            onClick = {},
                            label = {
                                Text(
                                    text = cap,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = BrandTokens.colorTextSecondary,
                                    ),
                                )
                            },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = BrandTokens.colorGlass,
                            ),
                            border = SuggestionChipDefaults.suggestionChipBorder(
                                enabled = true,
                                borderColor = BrandTokens.colorOutline,
                            ),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.lg))

            // Set Active button — hidden when already active
            if (!model.isActive) {
                Button(
                    onClick = onSetActive,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrandTokens.colorCyanPrimary,
                        contentColor = BrandTokens.colorAbyss,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.model_set_active),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.md))
        }
    }
}

// ── Private helpers ───────────────────────────────────────────────────────────

@Composable
private fun DetailRow(
    label: String,
    value: String,
    mono: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = BrandTokens.colorTextSecondary,
            ),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = if (mono) MonoFontFamily else null,
                color = BrandTokens.colorTextPrimary,
            ),
        )
    }
}
