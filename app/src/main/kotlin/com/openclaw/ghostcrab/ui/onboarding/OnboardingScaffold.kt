package com.openclaw.ghostcrab.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openclaw.ghostcrab.R
import com.openclaw.ghostcrab.domain.model.ONBOARDING_STEPS_COUNT
import com.openclaw.ghostcrab.domain.model.OnboardingStep
import com.openclaw.ghostcrab.domain.model.index
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.Spacing

/**
 * Shared scaffold for all onboarding steps.
 *
 * Provides a consistent chrome: optional back button, skip action, progress dots,
 * a step title, and a floating "?" help button that opens [TroubleshootingDrawer].
 *
 * @param step The current [OnboardingStep] used to render the progress indicator.
 * @param onBack Called when the back arrow is tapped. Pass `null` to hide the arrow (Welcome step).
 * @param onSkip Called when "Skip" is tapped.
 * @param title Step headline shown below the progress dots.
 * @param content The step-specific content rendered below the title.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun OnboardingScaffold(
    step: OnboardingStep,
    onBack: (() -> Unit)?,
    onSkip: () -> Unit,
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showDrawer by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BrandTokens.colorAbyss)
            .systemBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.md)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(Spacing.sm))

            // Top bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.onboarding_back_cd),
                            tint = BrandTokens.colorTextSecondary,
                        )
                    }
                } else {
                    // Placeholder to keep "Skip" right-aligned
                    Spacer(Modifier.size(48.dp))
                }

                TextButton(onClick = onSkip) {
                    Text(
                        text = stringResource(R.string.onboarding_skip),
                        color = BrandTokens.colorTextSecondary,
                    )
                }
            }

            Spacer(Modifier.height(Spacing.md))

            // Progress dots
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val currentIndex = step.index
                repeat(ONBOARDING_STEPS_COUNT) { i ->
                    val isCurrent = i == currentIndex
                    Box(
                        modifier = Modifier
                            .size(if (isCurrent) 8.dp else 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (isCurrent) BrandTokens.colorCyanPrimary
                                else BrandTokens.colorOutline,
                            ),
                    )
                }
            }

            Spacer(Modifier.height(Spacing.lg))

            // Title
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = BrandTokens.colorTextPrimary,
            )

            Spacer(Modifier.height(Spacing.md))

            // Step content
            content()

            // Bottom padding so FAB doesn't obscure content
            Spacer(Modifier.height(80.dp))
        }

        // Help FAB
        FloatingActionButton(
            onClick = { showDrawer = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(Spacing.md),
            containerColor = BrandTokens.colorGlass,
            contentColor = BrandTokens.colorTextSecondary,
        ) {
            Icon(
                imageVector = Icons.Default.QuestionMark,
                contentDescription = stringResource(R.string.onboarding_troubleshoot_title),
            )
        }

        if (showDrawer) {
            TroubleshootingDrawer(
                sheetState = sheetState,
                onDismiss = { showDrawer = false },
            )
        }
    }
}
