@file:Suppress("MatchingDeclarationName") // File is named for the screen; SettingsCallbacks is internal.

package com.openclaw.ghostcrab.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openclaw.ghostcrab.BuildConfig
import com.openclaw.ghostcrab.R
import com.openclaw.ghostcrab.domain.model.ConnectionProfile
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.Spacing
import org.koin.androidx.compose.koinViewModel

/**
 * Settings screen — profile management, app preferences, security actions, and app info.
 *
 * @param onNavigateBack Called to pop back to Dashboard.
 * @param onNavigateToInstalledSkills Opens the Installed Skills manager. Only invoked when
 *   `BuildConfig.SKILLS_INSTALL_ENABLED` is true; the screen hides its entry point otherwise.
 */
/**
 * Callbacks surfaced by the Settings screen. Bundled into one object so the downstream
 * composables don't blow past detekt's LongParameterList threshold.
 */
internal data class SettingsCallbacks(
    val onToggleCleartext: (Boolean) -> Unit,
    val onEditProfile: (ConnectionProfile) -> Unit,
    val onDeleteProfile: (ConnectionProfile) -> Unit,
    val onRequestClearAll: () -> Unit,
    val onReplayWalkthrough: () -> Unit,
    val onNavigateToInstalledSkills: () -> Unit,
)

/**
 * Settings screen: saved connections, onboarding replay, installed skills, security options, and About.
 *
 * @param onNavigateBack Called to pop back to the previous screen.
 * @param onNavigateToInstalledSkills Called when the user opens the installed-skills list.
 * @param viewModel Injected via Koin by default.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToInstalledSkills: () -> Unit = {},
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = androidx.compose.runtime.remember { SnackbarHostState() }
    val onboardingResetMessage = stringResource(R.string.settings_onboarding_reset_success)
    val readyState = androidx.compose.runtime.remember(state) { state as? SettingsUiState.Ready }

    LaunchedEffect(readyState?.onboardingResetSuccess) {
        if (readyState?.onboardingResetSuccess == true) {
            snackbarHostState.showSnackbar(onboardingResetMessage)
            viewModel.clearOnboardingResetSuccess()
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
        topBar = { SettingsTopBar(onNavigateBack) },
    ) { innerPadding ->
        SettingsBody(
            state = state,
            modifier = Modifier.padding(innerPadding),
            viewModel = viewModel,
            onNavigateToInstalledSkills = onNavigateToInstalledSkills,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTopBar(onNavigateBack: () -> Unit) {
    TopAppBar(
        title = {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.titleLarge.copy(color = BrandTokens.colorTextPrimary),
            )
        },
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.settings_back_cd),
                    tint = BrandTokens.colorTextPrimary,
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = BrandTokens.colorAbyss),
    )
}

@Composable
private fun SettingsBody(
    state: SettingsUiState,
    modifier: Modifier,
    viewModel: SettingsViewModel,
    onNavigateToInstalledSkills: () -> Unit,
) {
    when (val s = state) {
        is SettingsUiState.Loading -> {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = BrandTokens.colorCyanPrimary)
            }
        }
        is SettingsUiState.Ready -> {
            val callbacks = SettingsCallbacks(
                onToggleCleartext = viewModel::setAllowCleartextPublicIPs,
                onEditProfile = viewModel::startEditProfile,
                onDeleteProfile = { viewModel.requestDeleteProfile(it.id) },
                onRequestClearAll = viewModel::requestClearAllProfiles,
                onReplayWalkthrough = viewModel::replayWalkthrough,
                onNavigateToInstalledSkills = onNavigateToInstalledSkills,
            )
            ReadyContent(state = s, modifier = modifier, callbacks = callbacks)
            SettingsDialogs(state = s, viewModel = viewModel)
        }
    }
}

// ── Ready content ─────────────────────────────────────────────────────────────

@Composable
private fun ReadyContent(
    state: SettingsUiState.Ready,
    modifier: Modifier,
    callbacks: SettingsCallbacks,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.md),
    ) {
        Spacer(Modifier.height(Spacing.sm))
        ConnectionsSection(state, callbacks)
        Spacer(Modifier.height(Spacing.md))
        OnboardingSection(callbacks.onReplayWalkthrough)
        Spacer(Modifier.height(Spacing.md))
        if (BuildConfig.SKILLS_INSTALL_ENABLED) {
            SkillsSection(callbacks.onNavigateToInstalledSkills)
            Spacer(Modifier.height(Spacing.md))
        }
        SecuritySection(callbacks.onRequestClearAll)
        Spacer(Modifier.height(Spacing.md))
        AboutSection()
        Spacer(Modifier.height(Spacing.xxl))
    }
}
