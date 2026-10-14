package com.openclaw.ghostcrab.ui.airecommend

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.openclaw.ghostcrab.R
import com.openclaw.ghostcrab.ui.components.GlassSurface
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.MonoFontFamily
import com.openclaw.ghostcrab.ui.theme.Spacing

/**
 * Renders a successful recommendation result: summary, suggested changes and the apply / new-query actions.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecommendationReadyContent(
    state: AIRecommendationUiState.Ready,
    onNewQuery: () -> Unit,
    onApplySuggestions: () -> Unit,
) {
    val changes = state.recommendation.suggestedChanges
    Column(modifier = Modifier.fillMaxSize()) {
        // Recommendation text
        GlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(Spacing.md),
            ) {
                Text(
                    text = stringResource(R.string.ai_recommendations_label),
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = BrandTokens.colorCyanPrimary,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = state.recommendation.recommendation,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = BrandTokens.colorTextPrimary,
                    ),
                )

                // Suggested change summary chips
                if (changes.isNotEmpty()) {
                    Spacer(Modifier.height(Spacing.md))
                    Text(
                        text = stringResource(R.string.ai_suggestions_count, changes.size),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = BrandTokens.colorTextSecondary,
                        ),
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        changes.forEach { change ->
                            SuggestionChip(
                                onClick = {},
                                label = {
                                    Text(
                                        text = "${change.section}.${change.key}",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = MonoFontFamily,
                                            color = BrandTokens.colorCyanPulse,
                                        ),
                                    )
                                },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = BrandTokens.colorGlass,
                                ),
                                border = SuggestionChipDefaults.suggestionChipBorder(
                                    enabled = true,
                                    borderColor = BrandTokens.colorCyanPrimary.copy(alpha = 0.4f),
                                ),
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(Spacing.sm))

        // Action row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End),
        ) {
            TextButton(onClick = onNewQuery) {
                Text(
                    text = stringResource(R.string.ai_new_query),
                    color = BrandTokens.colorTextSecondary,
                )
            }

            if (changes.isNotEmpty()) {
                Button(
                    onClick = onApplySuggestions,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrandTokens.colorCyanPrimary,
                        contentColor = BrandTokens.colorAbyss,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.ai_apply_suggestions),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        Spacer(Modifier.height(Spacing.md))
    }
}
