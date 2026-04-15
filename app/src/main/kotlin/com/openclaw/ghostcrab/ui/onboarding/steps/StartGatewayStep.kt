package com.openclaw.ghostcrab.ui.onboarding.steps

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.openclaw.ghostcrab.R
import com.openclaw.ghostcrab.ui.components.CodeBlock
import com.openclaw.ghostcrab.ui.onboarding.OnboardingViewModel
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.Spacing

/**
 * Onboarding step that shows the gateway start command and token setup.
 *
 * @param onNext Called when the user taps "Next".
 */
@Composable
public fun StartGatewayStep(onNext: () -> Unit) {
    var token by rememberSaveable { mutableStateOf(OnboardingViewModel.generateToken()) }

    Column {
        Text(
            text = stringResource(R.string.onboarding_start_body),
            style = MaterialTheme.typography.bodyMedium,
            color = BrandTokens.colorTextSecondary,
        )

        Spacer(Modifier.height(Spacing.sm))

        CodeBlock(code = "openclaw gateway start --port 18789")

        Spacer(Modifier.height(Spacing.md))

        HorizontalDivider(color = BrandTokens.colorOutline)

        Spacer(Modifier.height(Spacing.md))

        Text(
            text = stringResource(R.string.onboarding_start_token_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = BrandTokens.colorTextSecondary,
        )

        Spacer(Modifier.height(Spacing.sm))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            CodeBlock(
                code = token,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { token = OnboardingViewModel.generateToken() }) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Regenerate token",
                    tint = BrandTokens.colorTextSecondary,
                )
            }
        }

        Spacer(Modifier.height(Spacing.sm))

        val jsonSnippet = """{"auth": {"type": "token", "token": "$token"}}"""
        CodeBlock(code = jsonSnippet)

        Spacer(Modifier.height(Spacing.sm))

        Text(
            text = stringResource(R.string.onboarding_start_token_warning),
            style = MaterialTheme.typography.bodySmall,
            color = BrandTokens.colorAmberWarn,
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
            Text(text = stringResource(R.string.onboarding_next))
        }
    }
}
