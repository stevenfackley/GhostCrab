package com.openclaw.ghostcrab.ui.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openclaw.ghostcrab.R
import com.openclaw.ghostcrab.ui.components.GlassSurface
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.Spacing

/**
 * A [ModalBottomSheet] drawer that displays common troubleshooting guidance.
 *
 * @param sheetState Controls the expanded/hidden state of the sheet.
 * @param onDismiss Called when the user dismisses the sheet.
 */
@Suppress("LongMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun TroubleshootingDrawer(
    sheetState: SheetState,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BrandTokens.colorAbyss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md)
                .navigationBarsPadding(),
        ) {
            Text(
                text = stringResource(R.string.onboarding_troubleshoot_title),
                style = MaterialTheme.typography.titleLarge,
                color = BrandTokens.colorTextPrimary,
            )
            Spacer(Modifier.height(Spacing.md))

            TroubleshootSection(
                title = stringResource(R.string.onboarding_troubleshoot_health_title),
                items = listOf(
                    stringResource(R.string.onboarding_troubleshoot_health_1),
                    stringResource(R.string.onboarding_troubleshoot_health_2),
                    stringResource(R.string.onboarding_troubleshoot_health_3),
                    stringResource(R.string.onboarding_troubleshoot_health_4),
                ),
            )
            Spacer(Modifier.height(Spacing.md))

            TroubleshootSection(
                title = stringResource(R.string.onboarding_troubleshoot_firewall_title),
                items = listOf(
                    stringResource(R.string.onboarding_troubleshoot_firewall_windows),
                    stringResource(R.string.onboarding_troubleshoot_firewall_macos),
                    stringResource(R.string.onboarding_troubleshoot_firewall_linux_ufw),
                    stringResource(R.string.onboarding_troubleshoot_firewall_linux_firewalld),
                    stringResource(R.string.onboarding_troubleshoot_firewall_test),
                ),
            )
            Spacer(Modifier.height(Spacing.md))

            TroubleshootSection(
                title = stringResource(R.string.onboarding_troubleshoot_scan_title),
                items = listOf(
                    stringResource(R.string.onboarding_troubleshoot_scan_1),
                    stringResource(R.string.onboarding_troubleshoot_scan_2),
                    stringResource(R.string.onboarding_troubleshoot_scan_3),
                ),
            )
            Spacer(Modifier.height(Spacing.md))

            TroubleshootSection(
                title = stringResource(R.string.onboarding_troubleshoot_auth_title),
                items = listOf(
                    stringResource(R.string.onboarding_troubleshoot_auth_1),
                    stringResource(R.string.onboarding_troubleshoot_auth_2),
                ),
            )
            Spacer(Modifier.height(Spacing.md))

            TroubleshootSection(
                title = stringResource(R.string.onboarding_troubleshoot_host_vs_lan_title),
                items = listOf(
                    stringResource(R.string.onboarding_troubleshoot_host_vs_lan_1),
                    stringResource(R.string.onboarding_troubleshoot_host_vs_lan_2),
                    stringResource(R.string.onboarding_troubleshoot_host_vs_lan_3),
                    stringResource(R.string.onboarding_troubleshoot_host_vs_lan_4),
                    stringResource(R.string.onboarding_troubleshoot_host_vs_lan_5),
                    stringResource(R.string.onboarding_troubleshoot_host_vs_lan_6),
                ),
            )
            Spacer(Modifier.height(Spacing.xxl))
        }
    }
}

@Composable
private fun TroubleshootSection(
    title: String,
    items: List<String>,
) {
    GlassSurface(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = BrandTokens.colorAmberWarn,
            )
            Spacer(Modifier.height(Spacing.sm))
            items.forEach { item ->
                Text(
                    text = "• $item",
                    style = MaterialTheme.typography.bodySmall,
                    color = BrandTokens.colorTextSecondary,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}
