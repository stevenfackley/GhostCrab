package com.openclaw.ghostcrab.ui.onboarding.steps

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
 * Onboarding step that explains what OpenClaw Gateway is.
 *
 * @param onNext Called when the user taps "Next".
 */
@Composable
public fun WhatIsOpenClawStep(onNext: () -> Unit) {
    Column {
        val bullets = listOf(
            stringResource(R.string.onboarding_bullet_gateway),
            stringResource(R.string.onboarding_bullet_models),
            stringResource(R.string.onboarding_bullet_auth),
            stringResource(R.string.onboarding_bullet_mdns),
        )

        bullets.forEach { bullet ->
            BulletItem(text = bullet)
            Spacer(Modifier.height(Spacing.md))
        }

        Spacer(Modifier.height(Spacing.md))

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
private fun BulletItem(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = BrandTokens.colorCyanPrimary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = BrandTokens.colorTextSecondary,
        )
    }
}
