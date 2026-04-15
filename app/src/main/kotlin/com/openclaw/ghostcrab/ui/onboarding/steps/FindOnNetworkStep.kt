package com.openclaw.ghostcrab.ui.onboarding.steps

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openclaw.ghostcrab.R
import com.openclaw.ghostcrab.ui.components.GlassSurface
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.Spacing

/**
 * Onboarding step that lets the user choose between auto-discovery and manual URL entry.
 *
 * @param onScan Called when the user taps "Auto-discover".
 * @param onManualEntry Called when the user taps "Enter URL manually".
 */
@Composable
public fun FindOnNetworkStep(
    onScan: () -> Unit,
    onManualEntry: () -> Unit,
) {
    Column {
        Text(
            text = stringResource(R.string.onboarding_find_body),
            style = MaterialTheme.typography.bodyMedium,
            color = BrandTokens.colorTextSecondary,
        )

        Spacer(Modifier.height(Spacing.lg))

        GlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onScan),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = BrandTokens.colorCyanPrimary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(Spacing.md))
                Column {
                    Text(
                        text = stringResource(R.string.onboarding_find_auto),
                        style = MaterialTheme.typography.titleSmall,
                        color = BrandTokens.colorCyanPrimary,
                    )
                    Text(
                        text = "Scan your local Wi-Fi for gateways",
                        style = MaterialTheme.typography.bodySmall,
                        color = BrandTokens.colorTextSecondary,
                    )
                }
            }
        }

        Spacer(Modifier.height(Spacing.md))

        GlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onManualEntry),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = null,
                    tint = BrandTokens.colorTextSecondary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(Spacing.md))
                Column {
                    Text(
                        text = stringResource(R.string.onboarding_find_manual),
                        style = MaterialTheme.typography.titleSmall,
                        color = BrandTokens.colorTextSecondary,
                    )
                    Text(
                        text = "Type the gateway URL directly",
                        style = MaterialTheme.typography.bodySmall,
                        color = BrandTokens.colorTextSecondary,
                    )
                }
            }
        }
    }
}
