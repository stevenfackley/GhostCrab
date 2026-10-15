package com.openclaw.ghostcrab.ui.airecommend

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openclaw.ghostcrab.R
import com.openclaw.ghostcrab.domain.model.SkillInstallError
import com.openclaw.ghostcrab.domain.model.SkillInstallProgress
import com.openclaw.ghostcrab.ui.components.CodeBlock
import com.openclaw.ghostcrab.ui.components.GlassSurface
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.Spacing
import com.openclaw.ghostcrab.ui.toUserMessage

/** @property command A CLI command for the gateway host, or `null` when there is nothing to run. */
private data class SkillUnavailableCopy(val title: String, val body: String, val command: String?)

@Composable
private fun rememberSkillUnavailableCopy(missingScope: String?): SkillUnavailableCopy {
    val isScopeGap = missingScope == "operator.admin"
    val title = stringResource(
        if (isScopeGap) R.string.ai_skill_unavailable_scope_title
        else R.string.ai_skill_unavailable_title,
    )
    val body = stringResource(
        if (isScopeGap) R.string.ai_skill_unavailable_scope_body
        else R.string.ai_skill_unavailable_body,
    )
    // Only the scope gap has something to run on the host; OpenClaw has no recommendation API to install.
    val command = if (isScopeGap) stringResource(R.string.ai_skill_unavailable_scope_command) else null
    return SkillUnavailableCopy(title, body, command)
}

/**
 * Shown when the gateway serves no AI recommendations. OpenClaw has none (ADR-0007): the copy says so and that
 * recommendations will return through the gateway's agent (`chat.send`) once a design is decided. A missing scope
 * and the install button / progress / error feedback appear only when the view model has a recommendation skill
 * to install (none today).
 */
@Composable
internal fun SkillUnavailableContent(
    missingScope: String?,
    canInstall: Boolean,
    installProgress: SkillInstallProgress?,
    installError: SkillInstallError?,
    onInstall: () -> Unit,
    onDismissError: () -> Unit,
    onCopied: () -> Unit,
) {
    val copy = rememberSkillUnavailableCopy(missingScope)
    val installing = installProgress != null
    // Mirror the CLI-code block hide logic: once install is in flight or the skill installer
    // is actively usable, the CLI copy is noise — keep the card focused on the in-app flow.
    val cliCommand = copy.command?.takeIf { !installing && installProgress !is SkillInstallProgress.Succeeded }
    val showInstallButton = canInstall && installProgress == null && installError == null
    val showLiveProgress = installProgress != null && installProgress !is SkillInstallProgress.Succeeded

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        GlassSurface(modifier = Modifier.padding(Spacing.md)) {
            Column(
                modifier = Modifier.padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    text = copy.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = if (missingScope != null) BrandTokens.colorCrimsonError
                        else BrandTokens.colorAmberWarn,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Text(
                    text = copy.body,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = BrandTokens.colorTextSecondary,
                    ),
                )
                if (cliCommand != null) {
                    Spacer(Modifier.height(Spacing.xs))
                    CodeBlock(code = cliCommand, onCopied = onCopied)
                }
                if (showInstallButton) {
                    Spacer(Modifier.height(Spacing.xs))
                    InstallButton(onInstall)
                }
                if (showLiveProgress) {
                    Spacer(Modifier.height(Spacing.xs))
                    InstallProgressRow(progress = installProgress)
                }
                if (installError != null) {
                    Spacer(Modifier.height(Spacing.xs))
                    InstallErrorRow(
                        error = installError,
                        onRetry = onInstall,
                        onDismiss = onDismissError,
                        canRetry = canInstall,
                    )
                }
            }
        }
    }
}

@Composable
private fun InstallButton(onInstall: () -> Unit) {
    Button(
        onClick = onInstall,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = BrandTokens.colorCyanPrimary,
            contentColor = BrandTokens.colorAbyss,
        ),
    ) {
        Text(
            text = stringResource(R.string.ai_skill_install_button),
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun InstallProgressRow(progress: SkillInstallProgress) {
    val label = when (progress) {
        is SkillInstallProgress.Connecting -> stringResource(R.string.ai_skill_install_connecting, progress.target)
        is SkillInstallProgress.Downloading -> progress.pct
            ?.let { stringResource(R.string.ai_skill_install_downloading, it) }
            ?: stringResource(R.string.ai_skill_install_downloading_indeterminate)
        is SkillInstallProgress.Verifying -> stringResource(R.string.ai_skill_install_verifying, progress.sha256Prefix)
        is SkillInstallProgress.Applying -> stringResource(R.string.ai_skill_install_applying, progress.step)
        SkillInstallProgress.Idle, is SkillInstallProgress.Failed, is SkillInstallProgress.Succeeded -> ""
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            color = BrandTokens.colorCyanPrimary,
            strokeWidth = 2.dp,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(color = BrandTokens.colorTextSecondary),
        )
    }
}

@Composable
private fun installErrorMessage(error: SkillInstallError): String {
    return when (error) {
        is SkillInstallError.Unauthorized -> stringResource(
            R.string.ai_skill_install_error_unauthorized,
            error.missingScope,
        )
        is SkillInstallError.NotFound -> stringResource(
            R.string.ai_skill_install_error_not_found,
            error.slug,
        )
        is SkillInstallError.DependencyConflict -> stringResource(
            R.string.ai_skill_install_error_dependency,
            error.conflicts.joinToString(", "),
        )
        is SkillInstallError.VerificationFailed -> stringResource(
            R.string.ai_skill_install_error_verification,
        )
        is SkillInstallError.Network -> stringResource(
            R.string.ai_skill_install_error_network,
            error.cause.toUserMessage(),
        )
        is SkillInstallError.Protocol -> stringResource(
            R.string.ai_skill_install_error_protocol,
            error.code,
            error.message,
        )
        is SkillInstallError.MalformedResponse -> stringResource(
            R.string.ai_skill_install_error_malformed,
            error.detail,
        )
        SkillInstallError.NotConnected -> stringResource(R.string.ai_skill_install_error_not_connected)
        is SkillInstallError.Unknown -> stringResource(
            R.string.ai_skill_install_error_unknown,
            error.cause.toUserMessage(),
        )
    }
}

@Composable
private fun InstallErrorRow(
    error: SkillInstallError,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val message = installErrorMessage(error)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall.copy(color = BrandTokens.colorCrimsonError),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.End),
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(R.string.ai_skill_install_error_dismiss),
                    color = BrandTokens.colorTextSecondary,
                )
            }
            if (error.isRetryable && canRetry) {
                TextButton(onClick = onRetry) {
                    Text(
                        stringResource(R.string.ai_skill_install_error_retry),
                        color = BrandTokens.colorCyanPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
