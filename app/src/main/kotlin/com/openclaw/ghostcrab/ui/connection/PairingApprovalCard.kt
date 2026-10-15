package com.openclaw.ghostcrab.ui.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openclaw.ghostcrab.R
import com.openclaw.ghostcrab.domain.model.ConnectionWarning
import com.openclaw.ghostcrab.ui.components.CodeBlock
import com.openclaw.ghostcrab.ui.components.GlassSurface
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.MonoFontFamily
import com.openclaw.ghostcrab.ui.theme.Spacing
import com.openclaw.ghostcrab.ui.toUserMessage

/**
 * Panel for `PAIRING_REQUIRED` (ADR-0007 decision 7): the reason, what explains the request (e.g. a regenerated
 * device identity), the request id, the exact command for the gateway host (copyable, JetBrains Mono) and the
 * requested scopes. GhostCrab keeps retrying in the background; nothing is approved from the phone. The spinner
 * marks an active wait (allowed motion: state transition / active scan).
 *
 * @param prompt What to approve.
 * @param onCheckNow Retry immediately instead of waiting for the next interval.
 * @param onCancel Stop waiting.
 * @param modifier Applied to the surface.
 */
@Composable
fun PairingApprovalCard(
    prompt: PairingPrompt,
    onCheckNow: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(modifier = modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            PairingHeader()
            prompt.warnings.forEach { PairingWarning(it) }
            PairingInstructions(prompt)
            Text(
                text = stringResource(R.string.pairing_retry_hint),
                style = MaterialTheme.typography.bodySmall,
                color = BrandTokens.colorTextSecondary,
            )
            PairingActions(onCheckNow = onCheckNow, onCancel = onCancel)
        }
    }
}

@Composable
private fun PairingHeader() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            color = BrandTokens.colorCyanPulse,
            strokeWidth = 2.dp,
        )
        Text(
            text = stringResource(R.string.pairing_title),
            style = MaterialTheme.typography.titleSmall,
            color = BrandTokens.colorAmberWarn,
        )
    }
}

/** Explains a [ConnectionWarning] next to the approval request; amber, as for every warning. */
@Composable
private fun PairingWarning(warning: ConnectionWarning) {
    val text = when (warning) {
        ConnectionWarning.DeviceIdentityRegenerated -> stringResource(R.string.pairing_warning_new_identity)
        is ConnectionWarning.DeviceTokenNotSaved ->
            stringResource(R.string.pairing_warning_token_not_saved, warning.cause.toUserMessage())
    }
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = BrandTokens.colorAmberWarn)
}

@Composable
private fun PairingInstructions(prompt: PairingPrompt) {
    Text(
        text = stringResource(R.string.pairing_body, prompt.reason),
        style = MaterialTheme.typography.bodySmall,
        color = BrandTokens.colorTextSecondary,
    )
    prompt.requestId?.let { id ->
        Text(
            text = stringResource(R.string.pairing_request_id, id),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
            color = BrandTokens.colorTextPrimary,
        )
    }
    CodeBlock(code = prompt.approveCommand)
    if (prompt.requestedScopes.isNotEmpty()) {
        Text(
            text = stringResource(R.string.pairing_scopes, prompt.requestedScopes.joinToString(", ")),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
            color = BrandTokens.colorTextSecondary,
        )
    }
}

@Composable
private fun PairingActions(onCheckNow: () -> Unit, onCancel: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Button(
            onClick = onCheckNow,
            colors = ButtonDefaults.buttonColors(
                containerColor = BrandTokens.colorCyanPrimary,
                contentColor = BrandTokens.colorAbyss,
            ),
        ) {
            Text(stringResource(R.string.pairing_check_now))
        }
        TextButton(onClick = onCancel) {
            Text(stringResource(R.string.pairing_cancel), color = BrandTokens.colorTextSecondary)
        }
    }
}
