package com.openclaw.ghostcrab.ui.onboarding.steps

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openclaw.ghostcrab.R
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.Spacing

/**
 * Terminal onboarding step shown after a successful first connection.
 *
 * @param onDone Called when the user taps "Go to Dashboard".
 */
@Composable
public fun ConnectedStep(onDone: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = BrandTokens.colorCyanPrimary,
            modifier = Modifier.size(64.dp),
        )

        Spacer(Modifier.height(Spacing.md))

        Text(
            text = stringResource(R.string.onboarding_connected_title),
            style = MaterialTheme.typography.headlineSmall,
            color = BrandTokens.colorTextPrimary,
        )

        Spacer(Modifier.height(Spacing.sm))

        Text(
            text = stringResource(R.string.onboarding_connected_body),
            style = MaterialTheme.typography.bodyMedium,
            color = BrandTokens.colorTextSecondary,
        )

        Spacer(Modifier.height(Spacing.xl))

        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = BrandTokens.colorCyanPrimary,
                contentColor = BrandTokens.colorAbyss,
            ),
        ) {
            Text(text = stringResource(R.string.onboarding_connected_cta))
        }
    }
}
