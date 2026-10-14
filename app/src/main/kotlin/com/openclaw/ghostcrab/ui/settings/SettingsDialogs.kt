package com.openclaw.ghostcrab.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.openclaw.ghostcrab.R
import com.openclaw.ghostcrab.domain.model.ConnectionProfile
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.Spacing

/**
 * Hosts the delete / clear-all / edit-profile dialogs driven by the Ready settings state.
 */
@Composable
internal fun SettingsDialogs(state: SettingsUiState.Ready, viewModel: SettingsViewModel) {
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

// ── Edit profile dialog ───────────────────────────────────────────────────────

@Composable
private fun EditProfileDialog(
    profile: ConnectionProfile,
    onSave: (displayName: String, newToken: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var displayName by rememberSaveable { mutableStateOf(profile.displayName) }
    var newToken by remember { mutableStateOf("") } // never rememberSaveable: Bundles are persisted

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

@Composable
private fun outlinedTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = BrandTokens.colorTextPrimary,
    unfocusedTextColor = BrandTokens.colorTextPrimary,
    focusedBorderColor = BrandTokens.colorCyanPrimary,
    unfocusedBorderColor = BrandTokens.colorOutline,
    cursorColor = BrandTokens.colorCyanPrimary,
)
