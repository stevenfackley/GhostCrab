package com.openclaw.ghostcrab.ui.onboarding.steps

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.openclaw.ghostcrab.R
import com.openclaw.ghostcrab.ui.components.CodeBlock
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.Spacing

/**
 * Onboarding step that instructs the user to verify the gateway is running.
 *
 * @param onNext Called when the user confirms the gateway is running.
 */
@Composable
public fun VerifyRunningStep(onNext: () -> Unit) {
    Column {
        // ── Verify on the host machine ─────────────────────────────────────────

        Text(
            text = stringResource(R.string.onboarding_verify_body),
            style = MaterialTheme.typography.bodyMedium,
            color = BrandTokens.colorTextSecondary,
        )

        Spacer(Modifier.height(Spacing.sm))

        CodeBlock(code = "http://localhost:18789/health")

        Spacer(Modifier.height(Spacing.md))

        Text(
            text = stringResource(R.string.onboarding_verify_expected),
            style = MaterialTheme.typography.bodyMedium,
            color = BrandTokens.colorTextSecondary,
        )

        Spacer(Modifier.height(Spacing.sm))

        CodeBlock(code = """{"status":"ok"}""")

        Spacer(Modifier.height(Spacing.md))
        HorizontalDivider(color = BrandTokens.colorOutline)
        Spacer(Modifier.height(Spacing.md))

        // ── Testing from phone ─────────────────────────────────────────────────

        Text(
            text = stringResource(R.string.onboarding_verify_phone_title),
            style = MaterialTheme.typography.titleSmall,
            color = BrandTokens.colorTextPrimary,
        )

        Spacer(Modifier.height(Spacing.xs))

        Text(
            text = stringResource(R.string.onboarding_verify_phone_body),
            style = MaterialTheme.typography.bodySmall,
            color = BrandTokens.colorTextSecondary,
        )

        Spacer(Modifier.height(Spacing.sm))

        CodeBlock(code = "http://[your-machine-ip]:18789/health")

        Spacer(Modifier.height(Spacing.sm))

        Text(
            text = stringResource(R.string.onboarding_verify_phone_ip_tip),
            style = MaterialTheme.typography.bodySmall,
            color = BrandTokens.colorTextSecondary,
        )

        Spacer(Modifier.height(Spacing.xl))

        OutlinedButton(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.onboarding_verify_cta),
                color = BrandTokens.colorCyanPrimary,
            )
        }
    }
}
