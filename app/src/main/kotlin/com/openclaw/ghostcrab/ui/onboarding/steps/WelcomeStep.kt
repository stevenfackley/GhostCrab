package com.openclaw.ghostcrab.ui.onboarding.steps

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.openclaw.ghostcrab.R
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.Spacing

/**
 * First onboarding step: welcome screen with two CTA buttons.
 *
 * @param onNext Called when the user taps "Get Started".
 * @param onSkip Called when the user taps the skip shortcut button.
 */
@Composable
public fun WelcomeStep(
    onNext: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Logo placeholder
        Text(
            text = "GhostCrab",
            style = MaterialTheme.typography.displaySmall,
            color = BrandTokens.colorCyanPrimary,
        )

        Spacer(Modifier.height(Spacing.lg))

        Text(
            text = stringResource(R.string.onboarding_welcome_body),
            style = MaterialTheme.typography.bodyLarge,
            color = BrandTokens.colorTextSecondary,
        )

        Spacer(Modifier.height(Spacing.xl))

        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = BrandTokens.colorCyanPrimary,
                contentColor = BrandTokens.colorAbyss,
            ),
        ) {
            Text(text = "Get Started")
        }

        Spacer(Modifier.height(Spacing.sm))

        OutlinedButton(
            onClick = onSkip,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Skip — I already have a Gateway",
                color = BrandTokens.colorTextSecondary,
            )
        }
    }
}
