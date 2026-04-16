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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onNavigateBack: () -> Unit) {
    val viewModel: SettingsViewModel = koinViewModel()
    val state by viewModel.state.collectAsState()
    val snackbarHostState = androidx.compose.runtime.remember { SnackbarHostState() }

    val onboardingResetMessage = stringResource(R.string.settings_onboarding_reset_success)

    val readyState = androidx.compose.runtime.remember(state) {
        state as? SettingsUiState.Ready
    }

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
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.titleLarge.copy(
                            color = BrandTokens.colorTextPrimary,
                        ),
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
        },
    ) { innerPadding ->
        when (val s = state) {
            is SettingsUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = BrandTokens.colorCyanPrimary)
                }
            }

            is SettingsUiState.Ready -> {
                ReadyContent(
                    state = s,
                    modifier = Modifier.padding(innerPadding),
                    onToggleCleartext = { viewModel.setAllowCleartextPublicIPs(it) },
                    onEditProfile = { viewModel.startEditProfile(it) },
                    onDeleteProfile = { viewModel.requestDeleteProfile(it.id) },
                    onRequestClearAll = { viewModel.requestClearAllProfiles() },
                    onReplayWalkthrough = { viewModel.replayWalkthrough() },
                )

                // Delete profile confirmation
                val pendingId = s.pendingDeleteProfileId
                if (pendingId != null) {
                    val profile = s.profiles.find { it.id == pendingId }
                    AlertDialog(
                        onDismissRequest = { viewModel.cancelDeleteProfile() },
                        title = {
                            Text(
                                stringResource(R.string.settings_delete_profile_title),
                                color = BrandTokens.colorTextPrimary,
                            )
                        },
                        text = {
                            Text(
                                stringResource(
                                    R.string.settings_delete_profile_body,
                                    profile?.displayName ?: pendingId,
                                ),
                                color = BrandTokens.colorTextSecondary,
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { viewModel.confirmDeleteProfile() }) {
                                Text(
                                    stringResource(R.string.settings_delete_confirm),
                                    color = BrandTokens.colorCrimsonError,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { viewModel.cancelDeleteProfile() }) {
                                Text(
                                    stringResource(R.string.settings_cancel),
                                    color = BrandTokens.colorTextSecondary,
                                )
                            }
                        },
                        containerColor = BrandTokens.colorAbyssRaised,
                    )
                }

                // Clear all confirmation
                if (s.showClearAllConfirmation) {
                    AlertDialog(
                        onDismissRequest = { viewModel.cancelClearAllProfiles() },
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
                            TextButton(onClick = { viewModel.confirmClearAllProfiles() }) {
                                Text(
                                    stringResource(R.string.settings_clear_all_confirm),
                                    color = BrandTokens.colorCrimsonError,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { viewModel.cancelClearAllProfiles() }) {
                                Text(
                                    stringResource(R.string.settings_cancel),
                                    color = BrandTokens.colorTextSecondary,
                                )
                            }
                        },
                        containerColor = BrandTokens.colorAbyssRaised,
                    )
                }

                // Edit profile sheet
                val editing = s.editingProfile
                if (editing != null) {
                    EditProfileDialog(
                        profile = editing,
                        onSave = { name, token -> viewModel.saveProfileEdit(name, token) },
                        onDismiss = { viewModel.cancelEditProfile() },
                    )
                }
            }
        }
    }
}

// ── Ready content ─────────────────────────────────────────────────────────────

@Composable
private fun ReadyContent(
    state: SettingsUiState.Ready,
    modifier: Modifier,
    onToggleCleartext: (Boolean) -> Unit,
    onEditProfile: (ConnectionProfile) -> Unit,
    onDeleteProfile: (ConnectionProfile) -> Unit,
    onRequestClearAll: () -> Unit,
    onReplayWalkthrough: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.md),
    ) {
        Spacer(Modifier.height(Spacing.sm))

        // ── Connections ───────────────────────────────────────────────────────
        SectionHeader(stringResource(R.string.settings_section_connections))

        GlassSurface(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Allow cleartext toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_cleartext_label),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = BrandTokens.colorTextPrimary,
                            ),
                        )
                        Text(
                            text = stringResource(R.string.settings_cleartext_hint),
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = BrandTokens.colorTextSecondary,
                            ),
                        )
                    }
                    Switch(
                        checked = state.allowCleartextPublicIPs,
                        onCheckedChange = onToggleCleartext,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = BrandTokens.colorAbyss,
                            checkedTrackColor = BrandTokens.colorAmberWarn,
                            uncheckedThumbColor = BrandTokens.colorTextSecondary,
                            uncheckedTrackColor = BrandTokens.colorGlass,
                        ),
                    )
                }

                if (state.profiles.isNotEmpty()) {
                    HorizontalDivider(
                        color = BrandTokens.colorOutline,
                        modifier = Modifier.padding(vertical = Spacing.sm),
                    )
                }

                // Profile list
                state.profiles.forEach { profile ->
                    ProfileRow(
                        profile = profile,
                        onEdit = { onEditProfile(profile) },
                        onDelete = { onDeleteProfile(profile) },
                    )
                }

                if (state.profiles.isEmpty()) {
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        text = stringResource(R.string.settings_no_profiles),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = BrandTokens.colorTextSecondary,
                        ),
                    )
                }
            }
        }

        Spacer(Modifier.height(Spacing.md))

        // ── Onboarding ────────────────────────────────────────────────────────
        SectionHeader(stringResource(R.string.settings_section_onboarding))

        GlassSurface(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.settings_replay_hint),
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = BrandTokens.colorTextSecondary,
                    ),
                )
                Spacer(Modifier.height(Spacing.sm))
                OutlinedButton(
                    onClick = onReplayWalkthrough,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.settings_replay_button),
                        color = BrandTokens.colorCyanPrimary,
                    )
                }
            }
        }

        Spacer(Modifier.height(Spacing.md))

        // ── Security ──────────────────────────────────────────────────────────
        SectionHeader(stringResource(R.string.settings_section_security))

        GlassSurface(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.settings_clear_all_hint),
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = BrandTokens.colorTextSecondary,
                    ),
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

        Spacer(Modifier.height(Spacing.md))

        // ── About ─────────────────────────────────────────────────────────────
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

        Spacer(Modifier.height(Spacing.xxl))
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
