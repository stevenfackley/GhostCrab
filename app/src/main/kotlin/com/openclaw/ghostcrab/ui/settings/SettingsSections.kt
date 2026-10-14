package com.openclaw.ghostcrab.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.openclaw.ghostcrab.BuildConfig
import com.openclaw.ghostcrab.R
import com.openclaw.ghostcrab.domain.model.ConnectionProfile
import com.openclaw.ghostcrab.ui.components.GlassSurface
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.MonoFontFamily
import com.openclaw.ghostcrab.ui.theme.Spacing

/** Connections block: profile list plus the cleartext-HTTP toggle. */
@Composable
internal fun ConnectionsSection(state: SettingsUiState.Ready, callbacks: SettingsCallbacks) {
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

/** Onboarding block: replay-walkthrough entry. */
@Composable
internal fun OnboardingSection(onReplay: () -> Unit) {
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

/** Skills block: entry point to manage installed skills. */
@Composable
internal fun SkillsSection(onManage: () -> Unit) {
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

/** Security block: clear-all-data action. */
@Composable
internal fun SecuritySection(onRequestClearAll: () -> Unit) {
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

/** About block: version and build information. */
@Composable
internal fun AboutSection() {
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
