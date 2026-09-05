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
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.openclaw.ghostcrab.R
import com.openclaw.ghostcrab.ui.components.CodeBlock
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.Spacing

private val FIREWALL_TABS = listOf("Windows", "macOS", "Linux (UFW)", "Linux (firewalld)")

private val FIREWALL_COMMANDS = listOf(
    """New-NetFirewallRule -DisplayName "OpenClaw Gateway" -Direction Inbound -Protocol TCP -LocalPort 18789 -Action Allow -Profile Private""",
    "sudo /usr/libexec/ApplicationFirewall/socketfilterfw --add \$(which openclaw)",
    "sudo ufw allow 18789/tcp",
    "sudo firewall-cmd --permanent --add-port=18789/tcp && sudo firewall-cmd --reload",
)

/**
 * Onboarding step that shows the gateway start command, firewall setup, and token configuration.
 *
 * The suggested token is owned by the ViewModel, not `rememberSaveable`: saved-instance state is
 * persisted to disk by the system and visible via `dumpsys`, which is no place for a secret.
 *
 * @param token Suggested bearer token to display for the user's gateway config.
 * @param onRegenerateToken Called when the user taps the regenerate button.
 * @param onNext Called when the user taps "Next".
 */
@Composable
public fun StartGatewayStep(
    token: String,
    onRegenerateToken: () -> Unit,
    onNext: () -> Unit,
) {
    Column {
        Text(
            text = stringResource(R.string.onboarding_start_body),
            style = MaterialTheme.typography.bodyMedium,
            color = BrandTokens.colorTextSecondary,
        )

        Spacer(Modifier.height(Spacing.sm))

        CodeBlock(code = "openclaw gateway start --port 18789")

        SectionDivider()

        FirewallSection()

        SectionDivider()

        TokenSection(token = token, onRegenerateToken = onRegenerateToken)

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

@Composable
private fun SectionDivider() {
    Spacer(Modifier.height(Spacing.md))
    HorizontalDivider(color = BrandTokens.colorOutline)
    Spacer(Modifier.height(Spacing.md))
}

@Composable
private fun FirewallSection() {
    var firewallTab by rememberSaveable { mutableIntStateOf(0) }

    Text(
        text = stringResource(R.string.onboarding_start_firewall_title),
        style = MaterialTheme.typography.titleSmall,
        color = BrandTokens.colorTextPrimary,
    )

    Spacer(Modifier.height(Spacing.xs))

    Text(
        text = stringResource(R.string.onboarding_start_firewall_body),
        style = MaterialTheme.typography.bodySmall,
        color = BrandTokens.colorTextSecondary,
    )

    Spacer(Modifier.height(Spacing.sm))

    ScrollableTabRow(
        selectedTabIndex = firewallTab,
        containerColor = BrandTokens.colorAbyss,
        contentColor = BrandTokens.colorCyanPrimary,
        edgePadding = Spacing.xs,
    ) {
        FIREWALL_TABS.forEachIndexed { index, label ->
            Tab(
                selected = firewallTab == index,
                onClick = { firewallTab = index },
                text = {
                    Text(
                        text = label,
                        color = if (firewallTab == index) BrandTokens.colorCyanPrimary
                        else BrandTokens.colorTextSecondary,
                    )
                },
            )
        }
    }

    Spacer(Modifier.height(Spacing.sm))

    CodeBlock(code = FIREWALL_COMMANDS[firewallTab])
}

/**
 * Token display + regenerate control + the JSON snippet to paste into `openclaw.json`.
 * Both code blocks are copied with the sensitive-clip flag.
 */
@Composable
private fun TokenSection(token: String, onRegenerateToken: () -> Unit) {
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
            sensitive = true,
        )
        IconButton(onClick = onRegenerateToken) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Regenerate token",
                tint = BrandTokens.colorTextSecondary,
            )
        }
    }

    Spacer(Modifier.height(Spacing.sm))

    val jsonSnippet = """{"auth": {"type": "token", "token": "$token"}}"""
    CodeBlock(code = jsonSnippet, sensitive = true)

    Spacer(Modifier.height(Spacing.sm))

    Text(
        text = stringResource(R.string.onboarding_start_token_warning),
        style = MaterialTheme.typography.bodySmall,
        color = BrandTokens.colorAmberWarn,
    )
}
