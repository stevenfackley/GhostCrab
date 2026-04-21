package com.openclaw.ghostcrab.ui.installedskills

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openclaw.ghostcrab.R
import com.openclaw.ghostcrab.domain.model.InstalledSkill
import com.openclaw.ghostcrab.domain.model.SkillSource
import com.openclaw.ghostcrab.ui.components.GlassSurface
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.MonoFontFamily
import com.openclaw.ghostcrab.ui.theme.Spacing
import org.koin.androidx.compose.koinViewModel
import java.text.DateFormat
import java.util.Date

/**
 * Installed Skills screen — lists gateway-installed skills with uninstall.
 *
 * Only reachable when `BuildConfig.SKILLS_INSTALL_ENABLED` is true.
 *
 * @param onNavigateBack Called to pop back to Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstalledSkillsScreen(onNavigateBack: () -> Unit) {
    val viewModel: InstalledSkillsViewModel = koinViewModel()
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val readyState = remember(state) { state as? InstalledSkillsUiState.Ready }
    val uninstallSuccessTemplate = stringResource(R.string.installed_skills_uninstall_success)

    LaunchedEffect(readyState?.uninstallSuccessSlug) {
        val slug = readyState?.uninstallSuccessSlug
        if (slug != null) {
            snackbarHostState.showSnackbar(uninstallSuccessTemplate.format(slug))
            viewModel.clearUninstallSuccess()
        }
    }
    LaunchedEffect(readyState?.errorMessage) {
        val msg = readyState?.errorMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearError()
        }
    }

    Scaffold(
        containerColor = BrandTokens.colorAbyss,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { InstalledSkillsTopBar(onNavigateBack, onRefresh = viewModel::refresh) },
    ) { innerPadding ->
        InstalledSkillsBody(
            state = state,
            innerPadding = innerPadding,
            onUninstallRequest = { viewModel.requestUninstall(it.slug) },
            onConfirmUninstall = viewModel::confirmUninstall,
            onCancelUninstall = viewModel::cancelUninstall,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstalledSkillsTopBar(onNavigateBack: () -> Unit, onRefresh: () -> Unit) {
    TopAppBar(
        title = {
            Text(
                text = stringResource(R.string.installed_skills_title),
                style = MaterialTheme.typography.titleLarge.copy(color = BrandTokens.colorTextPrimary),
            )
        },
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.installed_skills_back_cd),
                    tint = BrandTokens.colorTextPrimary,
                )
            }
        },
        actions = {
            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.installed_skills_refresh_cd),
                    tint = BrandTokens.colorCyanPrimary,
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = BrandTokens.colorAbyss),
    )
}

@Composable
private fun InstalledSkillsBody(
    state: InstalledSkillsUiState,
    innerPadding: androidx.compose.foundation.layout.PaddingValues,
    onUninstallRequest: (InstalledSkill) -> Unit,
    onConfirmUninstall: () -> Unit,
    onCancelUninstall: () -> Unit,
) {
    when (val s = state) {
        is InstalledSkillsUiState.Loading -> {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = BrandTokens.colorCyanPrimary)
            }
        }
        is InstalledSkillsUiState.Ready -> {
            if (s.skills.isEmpty()) {
                EmptyState(modifier = Modifier.padding(innerPadding))
            } else {
                SkillList(
                    state = s,
                    modifier = Modifier.padding(innerPadding),
                    onUninstall = onUninstallRequest,
                )
            }
            s.pendingUninstallSlug?.let { slug ->
                UninstallConfirmDialog(
                    slug = slug,
                    onConfirm = onConfirmUninstall,
                    onDismiss = onCancelUninstall,
                )
            }
        }
    }
}

@Composable
private fun UninstallConfirmDialog(slug: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.installed_skills_uninstall_title, slug),
                color = BrandTokens.colorTextPrimary,
            )
        },
        text = {
            Text(
                stringResource(R.string.installed_skills_uninstall_body),
                color = BrandTokens.colorTextSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(R.string.installed_skills_uninstall_confirm),
                    color = BrandTokens.colorCrimsonError,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(R.string.settings_cancel),
                    color = BrandTokens.colorTextSecondary,
                )
            }
        },
        containerColor = BrandTokens.colorAbyssRaised,
    )
}

@Composable
private fun EmptyState(modifier: Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(Spacing.xl),
        ) {
            Text(
                text = stringResource(R.string.installed_skills_empty_title),
                style = MaterialTheme.typography.titleMedium.copy(
                    color = BrandTokens.colorTextPrimary,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = stringResource(R.string.installed_skills_empty_body),
                style = MaterialTheme.typography.bodySmall.copy(
                    color = BrandTokens.colorTextSecondary,
                ),
            )
        }
    }
}

@Composable
private fun SkillList(
    state: InstalledSkillsUiState.Ready,
    modifier: Modifier,
    onUninstall: (InstalledSkill) -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.md),
    ) {
        Spacer(Modifier.height(Spacing.sm))

        GlassSurface(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                state.skills.forEachIndexed { index, skill ->
                    SkillRow(
                        skill = skill,
                        isUninstalling = state.uninstallingSlug == skill.slug,
                        onUninstall = { onUninstall(skill) },
                    )
                    if (index != state.skills.lastIndex) {
                        HorizontalDivider(
                            color = BrandTokens.colorOutline,
                            modifier = Modifier.padding(vertical = Spacing.sm),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(Spacing.xxl))
    }
}

@Composable
private fun SkillRow(
    skill: InstalledSkill,
    isUninstalling: Boolean,
    onUninstall: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        SkillDetails(skill = skill, modifier = Modifier.weight(1f))
        SkillRowTrailing(isUninstalling = isUninstalling, onUninstall = onUninstall)
    }
}

@Composable
private fun SkillDetails(skill: InstalledSkill, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = skill.slug,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = MonoFontFamily,
                color = BrandTokens.colorTextPrimary,
                fontWeight = FontWeight.SemiBold,
            ),
        )
        Spacer(Modifier.height(2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                text = stringResource(R.string.installed_skills_version, skill.installedVersion),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = MonoFontFamily,
                    color = BrandTokens.colorCyanPulse,
                ),
            )
            Text(
                text = "·",
                style = MaterialTheme.typography.bodySmall.copy(color = BrandTokens.colorTextSecondary),
            )
            Text(
                text = skill.source.label(),
                style = MaterialTheme.typography.bodySmall.copy(color = BrandTokens.colorTextSecondary),
            )
        }
        if (skill.installedAt > 0) {
            Text(
                text = stringResource(
                    R.string.installed_skills_installed_at,
                    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(skill.installedAt * 1000)),
                ),
                style = MaterialTheme.typography.bodySmall.copy(color = BrandTokens.colorTextDisabled),
            )
        }
    }
}

@Composable
private fun SkillRowTrailing(isUninstalling: Boolean, onUninstall: () -> Unit) {
    if (isUninstalling) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            color = BrandTokens.colorCrimsonError,
            strokeWidth = 2.dp,
        )
    } else {
        IconButton(onClick = onUninstall) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(R.string.installed_skills_uninstall_cd),
                tint = BrandTokens.colorCrimsonError,
            )
        }
    }
}

@Composable
private fun SkillSource.label(): String = when (this) {
    SkillSource.ClawHub -> stringResource(R.string.installed_skills_source_clawhub)
    SkillSource.Local -> stringResource(R.string.installed_skills_source_local)
    SkillSource.Unknown -> stringResource(R.string.installed_skills_source_unknown)
}
