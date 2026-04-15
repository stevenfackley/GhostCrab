package com.openclaw.ghostcrab.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.Spacing

/** Amber HTTP banner — shown whenever the active connection is not TLS. */
@Composable
fun HttpSecurityBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = BrandTokens.colorAmberWarn.copy(alpha = 0.15f),
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier
                .border(1.dp, BrandTokens.colorAmberWarn, MaterialTheme.shapes.small)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = BrandTokens.colorAmberWarn,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = "Connection is unencrypted (HTTP). Anyone on this network can read configuration data.",
                style = MaterialTheme.typography.bodySmall,
                color = BrandTokens.colorAmberWarn,
            )
        }
    }
}

/** Crimson no-auth banner — shown when the gateway accepts unauthenticated requests. */
@Composable
fun NoAuthSecurityBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = BrandTokens.colorCrimsonError.copy(alpha = 0.10f),
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier
                .border(1.dp, BrandTokens.colorCrimsonError, MaterialTheme.shapes.small)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = BrandTokens.colorCrimsonError,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = "This gateway accepts unauthenticated requests. Ensure it is not publicly reachable.",
                style = MaterialTheme.typography.bodySmall,
                color = BrandTokens.colorCrimsonError,
            )
        }
    }
}
