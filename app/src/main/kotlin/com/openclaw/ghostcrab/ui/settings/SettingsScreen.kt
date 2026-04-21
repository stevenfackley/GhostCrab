@file:Suppress("TooManyFunctions") // Compose screen split into many small @Composable helpers by design.

package com.openclaw.ghostcrab.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.openclaw.ghostcrab.BuildConfig
import com.openclaw.ghostcrab.R
import com.openclaw.ghostcrab.domain.model.ConnectionProfile
import com.openclaw.ghostcrab.ui.components.GlassSurface
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.MonoFontFamily
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
private data class SettingsCallbacks(
    val onToggleCleartext: (Boolean) -> Unit,
    val onEditProfile: (ConnectionProfile) -> Unit,
    val onDeleteProfile: (ConnectionProfile) -> Unit,
    val onRequestClearAll: () -> Unit,
    val onReplayWalkthrough: () -> Unit,
    val onNavigateToInstalledSkills: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToInstalledSkills: () -> Unit = {},
) {
    val viewModel: SettingsViewModel = koinViewModel()
    val state by viewModel.state.collectAsState()
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

@Composable
private fun SettingsDialogs(state: SettingsUiState.Ready, viewModel: SettingsViewModel) {
    state.pendingDeleteProfileId?.let { pendingId ->
        val profile = state.profiles.find { it.id == pendingId }
        DeleteProfileDialog(
            displayName = profile?.displayName ?: pendingId,
            onConfirm = viewModel::confirmDeleteProfile,
            onDismiss = viewModel::cancelDeleteProfile,
        )
    }
    if (state.showClearAllConfirmation) {
        ClearAllDialog(
            onConfirm = viewModel::confirmClearAllProfiles,
            onDismiss = viewModel::cancelClearAllProfiles,
        )
    }
    state.editingProfile?.let { editing ->
        EditProfileDialog(
            profile = editing,
            onSave = viewModel::saveProfileEdit,
            onDismiss = viewModel::cancelEditProfile,
        )
    }
}

@Composable
private fun DeleteProfileDialog(displayName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.settings_delete_profile_title),
                color = BrandTokens.colorTextPrimary,
            )
        },
        text = {
            Text(
                stringResource(R.string.settings_delete_profile_body, displayName),
                color = BrandTokens.colorTextSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(R.string.settings_delete_confirm),
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
private fun ClearAllDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.settings_clear_all_title),
                color = BrandTokens.colorTextPrimary,
            )
        },
        text = {
            Text(
                stringResource(R.string.settings_clear_all_body),
                color = BrandTokens.colorTextSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(R.string.settings_clear_all_confirm),
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

@Composable
private fun ConnectionsSection(state: SettingsUiState.Ready, callbacks: SettingsCallbacks) {
    SectionHeader(stringResource(R.string.settings_section_connections))
    GlassSurface(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            CleartextRow(checked = state.allowCleartextPublicIPs, onToggle = callbacks.onToggleCleartext)
            if (state.profiles.isNotEmpty()) {
                HorizontalDivider(
                    color = BrandTokens.colorOutline,
                    modifier = Modifier.padding(vertical = Spacing.sm),
                )
                state.profiles.forEach { profile ->
                    ProfileRow(
                        profile = profile,
                        onEdit = { callbacks.onEditProfile(profile) },
                        onDelete = { callbacks.onDeleteProfile(profile) },
                    )
                }
            } else {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = stringResource(R.string.settings_no_profiles),
                    style = MaterialTheme.typography.bodySmall.copy(color = BrandTokens.colorTextSecondary),
                )
            }
        }
    }
}

@Composable
private fun CleartextRow(checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_cleartext_label),
                style = MaterialTheme.typography.bodyMedium.copy(color = BrandTokens.colorTextPrimary),
            )
            Text(
                text = stringResource(R.string.settings_cleartext_hint),
                style = MaterialTheme.typography.bodySmall.copy(color = BrandTokens.colorTextSecondary),
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = BrandTokens.colorAbyss,
                checkedTrackColor = BrandTokens.colorAmberWarn,
                uncheckedThumbColor = BrandTokens.colorTextSecondary,
                uncheckedTrackColor = BrandTokens.colorGlass,
            ),
        )
    }
}

@Composable
private fun OnboardingSection(onReplay: () -> Unit) {
    SectionHeader(stringResource(R.string.settings_section_onboarding))
    GlassSurface(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.settings_replay_hint),
                style = MaterialTheme.typography.bodySmall.copy(color = BrandTokens.colorTextSecondary),
            )
            Spacer(Modifier.height(Spacing.sm))
            OutlinedButton(onClick = onReplay, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.settings_replay_button),
                    color = BrandTokens.colorCyanPrimary,
                )
            }
        }
    }
}

@Composable
private fun SkillsSection(onManage: () -> Unit) {
    SectionHeader(stringResource(R.string.settings_section_skills))
    GlassSurface(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.settings_skills_hint),
                style = MaterialTheme.typography.bodySmall.copy(color = BrandTokens.colorTextSecondary),
            )
            Spacer(Modifier.height(Spacing.sm))
            OutlinedButton(onClick = onManage, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.settings_skills_manage_button),
                    color = BrandTokens.colorCyanPrimary,
                )
            }
        }
    }
}

@Composable
private fun SecuritySection(onRequestClearAll: () -> Unit) {
    SectionHeader(stringResource(R.string.settings_section_security))
    GlassSurface(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.settings_clear_all_hint),
                style = MaterialTheme.typography.bodySmall.copy(color = BrandTokens.colorTextSecondary),
            )
            Spacer(Modifier.height(Spacing.sm))
            Button(
                onClick = onRequestClearAll,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandTokens.colorCrimsonError,
                    contentColor = BrandTokens.colorTextPrimary,
                ),
            ) {
                Text(
                    text = stringResource(R.string.settings_clear_all_button),
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun AboutSection() {
    SectionHeader(stringResource(R.string.settings_section_about))
    GlassSurface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            AboutRow(
                label = stringResource(R.string.settings_about_version),
                value = BuildConfig.VERSION_NAME,
            )
            AboutRow(
                label = stringResource(R.string.settings_about_build),
                value = BuildConfig.GIT_SHA,
                mono = true,
            )
            AboutRow(
                label = stringResource(R.string.settings_about_api_compat),
                value = stringResource(R.string.settings_about_api_compat_value),
            )
        }
    }
}

// ── Profile row ───────────────────────────────────────────────────────────────

@Composable
private fun ProfileRow(
    profile: ConnectionProfile,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.displayName,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = BrandTokens.colorTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Text(
                text = profile.url,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = MonoFontFamily,
                    color = BrandTokens.colorTextSecondary,
                ),
            )
        }
        IconButton(onClick = onEdit) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = stringResource(R.string.settings_edit_profile_cd),
                tint = BrandTokens.colorCyanPrimary,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(R.string.settings_delete_profile_cd),
                tint = BrandTokens.colorCrimsonError,
            )
        }
    }
}

// ── Edit profile dialog ───────────────────────────────────────────────────────

@Composable
private fun EditProfileDialog(
    profile: ConnectionProfile,
    onSave: (displayName: String, newToken: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var displayName by rememberSaveable { mutableStateOf(profile.displayName) }
    var newToken by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BrandTokens.colorAbyssRaised,
        title = {
            Text(
                stringResource(R.string.settings_edit_profile_title),
                color = BrandTokens.colorTextPrimary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = {
                        Text(
                            stringResource(R.string.settings_edit_display_name_label),
                            color = BrandTokens.colorTextSecondary,
                        )
                    },
                    singleLine = true,
                    colors = outlinedTextFieldColors(),
                )
                OutlinedTextField(
                    value = newToken,
                    onValueChange = { newToken = it },
                    label = {
                        Text(
                            stringResource(R.string.settings_edit_token_label),
                            color = BrandTokens.colorTextSecondary,
                        )
                    },
                    placeholder = {
                        Text(
                            stringResource(
                                if (profile.hasToken) {
                                    R.string.settings_edit_token_replace_hint
                                } else {
                                    R.string.settings_edit_token_empty_hint
                                },
                            ),
                            color = BrandTokens.colorTextDisabled,
                        )
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    colors = outlinedTextFieldColors(),
                )
                Text(
                    text = stringResource(R.string.settings_edit_token_note),
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = BrandTokens.colorTextSecondary,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(displayName, if (newToken.isBlank()) null else newToken)
                },
                enabled = displayName.isNotBlank(),
            ) {
                Text(
                    stringResource(R.string.settings_edit_save),
                    color = BrandTokens.colorCyanPrimary,
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
    )
}

// ── Shared helpers ────────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(
            color = BrandTokens.colorCyanPrimary,
            fontWeight = FontWeight.Bold,
        ),
        modifier = Modifier.padding(bottom = Spacing.xs),
    )
}

@Composable
private fun AboutRow(label: String, value: String, mono: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(color = BrandTokens.colorTextSecondary),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = if (mono) MonoFontFamily else null,
                color = BrandTokens.colorTextPrimary,
            ),
        )
    }
}

@Composable
private fun outlinedTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = BrandTokens.colorTextPrimary,
    unfocusedTextColor = BrandTokens.colorTextPrimary,
    focusedBorderColor = BrandTokens.colorCyanPrimary,
    unfocusedBorderColor = BrandTokens.colorOutline,
    cursorColor = BrandTokens.colorCyanPrimary,
)
