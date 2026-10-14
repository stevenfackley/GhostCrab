package com.openclaw.ghostcrab.ui.airecommend

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

private data class SkillUnavailableCopy(val title: String, val body: String, val command: String)

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
    val command = stringResource(
        if (isScopeGap) R.string.ai_skill_unavailable_scope_command
        else R.string.ai_skill_unavailable_hint,
    )
    return SkillUnavailableCopy(title, body, command)
}

/**
 * Shown when the AI recommendation skill is not installed (or lacks a scope) on the gateway;
 * offers an install button and install progress / error feedback.
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
    val showCliBlock = !installing && installProgress !is SkillInstallProgress.Succeeded
    val showInstallButton = canInstall && installProgress == null && installError == null
    val showLiveProgress = installProgress != null && installProgress !is SkillInstallProgress.Succeeded
    val showBrowserButton = missingScope == null && !installing

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
                if (showCliBlock) {
                    Spacer(Modifier.height(Spacing.xs))
                    CodeBlock(code = copy.command, onCopied = onCopied)
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
                if (showBrowserButton) {
                    Spacer(Modifier.height(Spacing.xs))
                    OpenBrowserButton()
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
private fun OpenBrowserButton() {
    val context = LocalContext.current
    val clawhubUrl = stringResource(R.string.ai_skill_unavailable_clawhub_url)
    val noBrowserMessage = stringResource(R.string.ai_skill_open_browser_unavailable, clawhubUrl)
    OutlinedButton(
        onClick = {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(clawhubUrl)))
            } catch (_: ActivityNotFoundException) {
                // Locked-down profiles can have no browser; say so instead of crashing.
                Toast.makeText(context, noBrowserMessage, Toast.LENGTH_LONG).show()
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = Icons.Default.OpenInBrowser,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = BrandTokens.colorCyanPrimary,
        )
        Spacer(Modifier.size(Spacing.xs))
        Text(
            text = stringResource(R.string.ai_skill_unavailable_open_browser),
            color = BrandTokens.colorCyanPrimary,
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
private fun InstallErrorRow(
    error: SkillInstallError,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val message = when (error) {
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
            error.cause,
        )
        is SkillInstallError.Protocol -> stringResource(
            R.string.ai_skill_install_error_protocol,
            error.rpcCode,
            error.message,
        )
        SkillInstallError.NotConnected -> stringResource(R.string.ai_skill_install_error_not_connected)
        is SkillInstallError.Unknown -> stringResource(
            R.string.ai_skill_install_error_unknown,
            error.cause,
        )
    }
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
