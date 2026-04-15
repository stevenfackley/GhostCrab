package com.openclaw.ghostcrab.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.openclaw.ghostcrab.domain.model.AuthRequirement
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.MonoFontFamily
import com.openclaw.ghostcrab.ui.theme.Spacing

/**
 * Top-bar status strip showing current connection state, protocol badge, and auth mode pill.
 *
 * Renders nothing when [state] is [GatewayConnection.Disconnected].
 */
@Composable
fun ConnectionStatusBar(
    state: GatewayConnection,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is GatewayConnection.Disconnected -> Unit
        is GatewayConnection.Connecting -> {
            StatusRow(modifier = modifier) {
                StatusDot(color = BrandTokens.colorAmberWarn)
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text = "Connecting…",
                    style = MaterialTheme.typography.labelMedium,
                    color = BrandTokens.colorTextSecondary,
                )
                Spacer(Modifier.width(Spacing.sm))
                MonoText(state.url)
            }
        }
        is GatewayConnection.Connected -> {
            StatusRow(modifier = modifier) {
                StatusDot(color = BrandTokens.colorCyanPrimary)
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text = state.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = BrandTokens.colorTextPrimary,
                )
                Spacer(Modifier.width(Spacing.sm))
                ProtocolBadge(isHttps = state.isHttps)
                Spacer(Modifier.width(Spacing.xs))
                AuthBadge(authRequirement = state.authRequirement)
            }
        }
        is GatewayConnection.Error -> {
            StatusRow(modifier = modifier) {
                StatusDot(color = BrandTokens.colorCrimsonError)
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text = "Error",
                    style = MaterialTheme.typography.labelMedium,
                    color = BrandTokens.colorCrimsonError,
                )
                Spacer(Modifier.width(Spacing.sm))
                MonoText(state.url)
            }
        }
    }
}

@Composable
private fun StatusRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        content()
    }
}

@Composable
private fun StatusDot(color: Color) {
    Surface(
        modifier = Modifier.size(8.dp),
        shape = RoundedCornerShape(50),
        color = color,
        content = {},
    )
}

@Composable
private fun ProtocolBadge(isHttps: Boolean) {
    val label = if (isHttps) "HTTPS" else "HTTP"
    val color = if (isHttps) BrandTokens.colorCyanPrimary else BrandTokens.colorAmberWarn
    Pill(label = label, color = color)
}

@Composable
private fun AuthBadge(authRequirement: AuthRequirement) {
    when (authRequirement) {
        AuthRequirement.Token -> Pill("TOKEN", BrandTokens.colorCyanPulse)
        AuthRequirement.None -> Pill("NO AUTH", BrandTokens.colorCrimsonError)
    }
}

@Composable
private fun Pill(label: String, color: Color) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun MonoText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
        color = BrandTokens.colorTextSecondary,
        maxLines = 1,
    )
}
