package com.openclaw.ghostcrab.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openclaw.ghostcrab.R
import com.openclaw.ghostcrab.domain.model.AuthRequirement
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import com.openclaw.ghostcrab.domain.model.ModelInfo
import com.openclaw.ghostcrab.ui.components.GlassSurface
import com.openclaw.ghostcrab.ui.components.HttpSecurityBanner
import com.openclaw.ghostcrab.ui.components.NoAuthSecurityBanner
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.Spacing
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateBack: () -> Unit,
    onNavigateToConfig: () -> Unit,
    onNavigateToModels: () -> Unit,
    onNavigateToAiRecommend: () -> Unit,
    viewModel: DashboardViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state) {
        if (state is DashboardUiState.Disconnected) onNavigateBack()
    }

    Scaffold(
        containerColor = BrandTokens.colorAbyss,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dashboard_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
                actions = {
                    OutlinedButton(
                        onClick = { viewModel.disconnect() },
                        modifier = Modifier.padding(end = Spacing.sm),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = BrandTokens.colorCrimsonError),
                    ) {
                        Text(stringResource(R.string.dashboard_disconnect))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BrandTokens.colorAbyss,
                    titleContentColor = BrandTokens.colorTextPrimary,
                    navigationIconContentColor = BrandTokens.colorTextPrimary,
                ),
            )
        },
    ) { innerPadding ->
        when (val s = state) {
            is DashboardUiState.Loading -> LoadingContent(Modifier.padding(innerPadding))
            is DashboardUiState.Ready -> ReadyContent(
                state = s,
                onNavigateToConfig = onNavigateToConfig,
                onNavigateToModels = onNavigateToModels,
                onNavigateToAiRecommend = onNavigateToAiRecommend,
                onDisconnect = { viewModel.disconnect() },
                modifier = Modifier.padding(innerPadding),
            )
            is DashboardUiState.Degraded -> DegradedContent(
                reason = s.reason,
                onDisconnect = { viewModel.disconnect() },
                modifier = Modifier.padding(innerPadding),
            )
            is DashboardUiState.Disconnected -> Unit // LaunchedEffect handles navigation
        }
    }
}

// ── Loading ───────────────────────────────────────────────────────────────────

@Composable
private fun LoadingContent(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = BrandTokens.colorCyanPrimary)
    }
}

// ── Ready ─────────────────────────────────────────────────────────────────────

@Composable
private fun ReadyContent(
    state: DashboardUiState.Ready,
    onNavigateToConfig: () -> Unit,
    onNavigateToModels: () -> Unit,
    onNavigateToAiRecommend: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        // Security banners
        val conn = state.connection
        if (!conn.isHttps) HttpSecurityBanner()
        if (conn.authRequirement is AuthRequirement.None) NoAuthSecurityBanner()

        // Gateway header card
        GatewayHeaderCard(conn)

        // Health card
        HealthCard(state.health)

        // Model summary card
        ModelSummaryCard(state.models)

        // Quick actions
        QuickActionsRow(
            onNavigateToConfig = onNavigateToConfig,
            onNavigateToModels = onNavigateToModels,
            onNavigateToAiRecommend = onNavigateToAiRecommend,
            onDisconnect = onDisconnect,
        )

        Spacer(Modifier.height(Spacing.lg))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GatewayHeaderCard(conn: GatewayConnection.Connected) {
    GlassSurface(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(
                text = conn.displayName,
                style = MaterialTheme.typography.headlineSmall,
                color = BrandTokens.colorTextPrimary,
            )
            Text(
                text = conn.url,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = BrandTokens.colorTextSecondary,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VersionPill(conn.version)
                if (conn.capabilities.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        conn.capabilities.forEach { CapabilityChip(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun VersionPill(version: String) {
    Surface(
        color = BrandTokens.colorCyanPrimary.copy(alpha = 0.12f),
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = stringResource(R.string.dashboard_version_pill, version),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = BrandTokens.colorCyanPrimary,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        )
    }
}

@Composable
private fun CapabilityChip(capability: String) {
    Surface(
        color = BrandTokens.colorGlass,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = capability,
            style = MaterialTheme.typography.labelSmall,
            color = BrandTokens.colorTextSecondary,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        )
    }
}

@Composable
private fun HealthCard(health: HealthSnapshot) {
    GlassSurface(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(
                text = stringResource(R.string.dashboard_health_card_title),
                style = MaterialTheme.typography.titleSmall,
                color = BrandTokens.colorTextPrimary,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                val dotColor = when {
                    health.lastError != null -> BrandTokens.colorCrimsonError
                    health.isStale -> BrandTokens.colorAmberWarn
                    health.lastOkMs != null -> BrandTokens.colorCyanPrimary
                    else -> BrandTokens.colorTextDisabled
                }
                Surface(
                    modifier = Modifier.size(10.dp),
                    shape = CircleShape,
                    color = dotColor,
                    content = {},
                )
                val statusText = when {
                    health.lastError != null ->
                        stringResource(R.string.dashboard_health_error, health.lastError)
                    health.lastOkMs != null ->
                        stringResource(R.string.dashboard_health_last_ok, formatElapsed(health.lastOkMs))
                    else ->
                        stringResource(R.string.dashboard_health_never_checked)
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = BrandTokens.colorTextSecondary,
                )
            }
        }
    }
}

@Composable
private fun ModelSummaryCard(models: List<ModelInfo>) {
    GlassSurface(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(
                text = stringResource(R.string.dashboard_models_card_title),
                style = MaterialTheme.typography.titleSmall,
                color = BrandTokens.colorTextPrimary,
            )
            if (models.isEmpty()) {
                Text(
                    text = stringResource(R.string.dashboard_models_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = BrandTokens.colorTextSecondary,
                )
            } else {
                Text(
                    text = stringResource(R.string.dashboard_models_count, models.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = BrandTokens.colorTextSecondary,
                )
                models.firstOrNull { it.isActive }?.let { active ->
                    Text(
                        text = stringResource(R.string.dashboard_models_active, active.displayName),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = BrandTokens.colorCyanPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickActionsRow(
    onNavigateToConfig: () -> Unit,
    onNavigateToModels: () -> Unit,
    onNavigateToAiRecommend: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        OutlinedButton(
            onClick = onNavigateToConfig,
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.dashboard_action_configure)) }

        OutlinedButton(
            onClick = onNavigateToModels,
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.dashboard_action_models)) }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        OutlinedButton(
            onClick = onNavigateToAiRecommend,
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.dashboard_action_ai_recommend)) }

        OutlinedButton(
            onClick = onDisconnect,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = BrandTokens.colorCrimsonError),
        ) { Text(stringResource(R.string.dashboard_disconnect)) }
    }
}

// ── Degraded ──────────────────────────────────────────────────────────────────

@Composable
private fun DegradedContent(
    reason: String,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        GlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xl),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Text(
                    text = stringResource(R.string.dashboard_degraded_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = BrandTokens.colorAmberWarn,
                )
                Text(
                    text = stringResource(R.string.dashboard_degraded_body, reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = BrandTokens.colorTextSecondary,
                )
                Spacer(Modifier.width(0.dp))
                Button(
                    onClick = onDisconnect,
                    colors = ButtonDefaults.buttonColors(containerColor = BrandTokens.colorCrimsonError),
                ) {
                    Text(stringResource(R.string.dashboard_degraded_disconnect))
                }
            }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun formatElapsed(epochMs: Long): String {
    val elapsed = System.currentTimeMillis() - epochMs
    return when {
        elapsed < 60_000L -> "${elapsed / 1000}s"
        elapsed < 3_600_000L -> "${elapsed / 60_000}m"
        else -> "${elapsed / 3_600_000}h"
    }
}
