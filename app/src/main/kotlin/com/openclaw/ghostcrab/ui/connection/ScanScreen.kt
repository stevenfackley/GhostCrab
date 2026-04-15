package com.openclaw.ghostcrab.ui.connection

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.openclaw.ghostcrab.domain.model.DiscoveredGateway
import com.openclaw.ghostcrab.ui.components.GlassSurface
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.MonoFontFamily
import com.openclaw.ghostcrab.ui.theme.Spacing
import org.koin.androidx.compose.koinViewModel

/**
 * LAN discovery screen.
 *
 * Auto-starts a 10 s mDNS scan on entry. Discovered gateways appear in real time as glass cards.
 * Tapping a result either connects immediately (known profile) or navigates to ManualEntry with
 * the URL pre-filled.
 *
 * @param onNavigateBack Navigate up to ConnectionPicker.
 * @param onNavigateToManualEntry Navigate to ManualEntry with [prefillUrl] pre-populated.
 * @param onNavigateToDashboard Navigate to Dashboard after a successful connection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    onNavigateBack: () -> Unit,
    onNavigateToManualEntry: (String) -> Unit,
    onNavigateToDashboard: () -> Unit,
    viewModel: ScanViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) { viewModel.startScan() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ScanEvent.NavigateToManualEntry -> onNavigateToManualEntry(event.prefillUrl)
                ScanEvent.NavigateToDashboard -> onNavigateToDashboard()
            }
        }
    }

    val isActivelyScanning = state is ScanState.Scanning ||
        (state is ScanState.Results && !(state as ScanState.Results).scanCompleted)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Scan LAN",
                        style = MaterialTheme.typography.titleMedium,
                        color = BrandTokens.colorTextPrimary,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = BrandTokens.colorTextSecondary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BrandTokens.colorAbyss,
                ),
            )
        },
        containerColor = BrandTokens.colorAbyss,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (val s = state) {
                ScanState.Idle -> {}

                is ScanState.Scanning -> ScanningContent()

                is ScanState.Results -> ResultsContent(
                    gateways = s.gateways,
                    isScanning = !s.scanCompleted,
                    onGatewayTapped = viewModel::onGatewaySelected,
                    onScanAgain = viewModel::startScan,
                )

                is ScanState.Error -> ErrorContent(
                    reason = s.reason,
                    onRetry = viewModel::startScan,
                )
            }
        }
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun ScanningContent() {
    val infiniteTransition = rememberInfiniteTransition(label = "scan_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse_alpha",
    )
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse_scale",
    )

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Wifi,
            contentDescription = null,
            tint = BrandTokens.colorCyanPulse,
            modifier = Modifier
                .size(72.dp)
                .graphicsLayer {
                    alpha = pulseAlpha
                    scaleX = pulseScale
                    scaleY = pulseScale
                },
        )
        Spacer(Modifier.height(Spacing.lg))
        Text(
            text = "Scanning for OpenClaw gateways\u2026",
            style = MaterialTheme.typography.bodyMedium,
            color = BrandTokens.colorTextSecondary,
        )
    }
}

@Composable
private fun ResultsContent(
    gateways: List<DiscoveredGateway>,
    isScanning: Boolean,
    onGatewayTapped: (DiscoveredGateway) -> Unit,
    onScanAgain: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Pulse banner while still scanning
        if (isScanning) {
            ScanningBanner()
        }

        if (gateways.isEmpty() && !isScanning) {
            EmptyResultsContent(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                items(gateways, key = { it.instanceName }) { gateway ->
                    GatewayCard(
                        gateway = gateway,
                        onClick = { onGatewayTapped(gateway) },
                    )
                }
            }
        }

        if (!isScanning) {
            OutlinedButton(
                onClick = onScanAgain,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.md),
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(Spacing.xs))
                Text("Scan again")
            }
        }
    }
}

/** Compact pulse indicator shown at the top while results are streaming in. */
@Composable
private fun ScanningBanner() {
    val infiniteTransition = rememberInfiniteTransition(label = "banner_pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "banner_alpha",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Scanning\u2026",
            style = MaterialTheme.typography.labelSmall,
            color = BrandTokens.colorCyanPulse.copy(alpha = alpha),
        )
    }
}

@Composable
private fun GatewayCard(
    gateway: DiscoveredGateway,
    onClick: () -> Unit,
) {
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column {
            Text(
                text = gateway.displayName,
                style = MaterialTheme.typography.titleSmall,
                color = BrandTokens.colorTextPrimary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = gateway.url,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                color = BrandTokens.colorTextSecondary,
            )
            if (gateway.version != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "v${gateway.version}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                    color = BrandTokens.colorCyanPulse,
                )
            }
        }
    }
}

@Composable
private fun EmptyResultsContent(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = "No OpenClaw gateways detected on this network.\n" +
                "mDNS may be blocked. Enter a URL manually.",
            style = MaterialTheme.typography.bodyMedium,
            color = BrandTokens.colorTextSecondary,
        )
    }
}

@Composable
private fun ErrorContent(
    reason: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = reason,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
            color = BrandTokens.colorCrimsonError,
        )
        Spacer(Modifier.height(Spacing.lg))
        OutlinedButton(onClick = onRetry) {
            Text("Try again")
        }
    }
}
