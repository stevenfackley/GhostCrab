package com.openclaw.ghostcrab.ui.connection

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openclaw.ghostcrab.R
import com.openclaw.ghostcrab.domain.model.ConnectionProfile
import com.openclaw.ghostcrab.ui.components.GlassSurface
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.MonoFontFamily
import com.openclaw.ghostcrab.ui.theme.Spacing
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionPickerScreen(
    onNavigateToManualEntry: () -> Unit,
    onNavigateToScan: () -> Unit,
    onNavigateToQrScan: () -> Unit,
    onNavigateToDashboard: () -> Unit,
    onNavigateToOnboarding: () -> Unit = {},
    viewModel: ConnectionPickerViewModel = koinViewModel(),
) {
    val profiles by viewModel.profiles.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var connectingId by remember { mutableStateOf<String?>(null) }

    val showOnboarding by viewModel.showOnboarding.collectAsState()
    LaunchedEffect(showOnboarding) {
        if (showOnboarding) {
            onNavigateToOnboarding()
            viewModel.onOnboardingNavigated()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Image(
                        painter = painterResource(R.drawable.logo_ghostcrab),
                        contentDescription = "GhostCrab",
                        modifier = Modifier.height(38.dp),
                        contentScale = ContentScale.Fit,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BrandTokens.colorAbyss,
                ),
                actions = {
                    IconButton(onClick = onNavigateToQrScan) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = "Scan QR Code",
                            tint = BrandTokens.colorCyanPrimary,
                        )
                    }
                    IconButton(onClick = onNavigateToScan) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Scan LAN",
                            tint = BrandTokens.colorTextSecondary,
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (profiles.isNotEmpty()) {
                FloatingActionButton(
                    onClick = onNavigateToManualEntry,
                    containerColor = BrandTokens.colorCyanPrimary,
                    contentColor = BrandTokens.colorAbyss,
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add gateway")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = BrandTokens.colorAbyss,
    ) { innerPadding ->
        if (profiles.isEmpty()) {
            EmptyState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                onScanQr = onNavigateToQrScan,
                onScanLan = onNavigateToScan,
                onManualEntry = onNavigateToManualEntry,
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                items(profiles, key = { it.id }) { profile ->
                    ProfileCard(
                        profile = profile,
                        isConnecting = connectingId == profile.id,
                        onConnect = {
                            connectingId = profile.id
                            viewModel.connect(profile) { result ->
                                connectingId = null
                                result.onSuccess { onNavigateToDashboard() }
                                    .onFailure { e ->
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                e.message ?: "Connection failed"
                                            )
                                        }
                                    }
                            }
                        },
                        onDelete = { viewModel.delete(profile.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(
    profile: ConnectionProfile,
    isConnecting: Boolean,
    onConnect: () -> Unit,
    onDelete: () -> Unit,
) {
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isConnecting, onClick = onConnect),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = BrandTokens.colorTextPrimary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = profile.url,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                    color = BrandTokens.colorTextSecondary,
                )
                if (profile.hasToken) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "TOKEN AUTH",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                        color = BrandTokens.colorCyanPulse,
                    )
                }
            }
            Spacer(Modifier.width(Spacing.sm))
            if (isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(Spacing.sm),
                    color = BrandTokens.colorCyanPrimary,
                    strokeWidth = 2.dp,
                )
            } else {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete profile",
                        tint = BrandTokens.colorTextSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(
    modifier: Modifier,
    onScanQr: () -> Unit,
    onScanLan: () -> Unit,
    onManualEntry: () -> Unit,
) {
    Column(
        modifier = modifier.padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(BrandTokens.colorCyanPrimary.copy(alpha = 0.06f))
                .border(
                    width = 1.dp,
                    color = BrandTokens.colorCyanPrimary.copy(alpha = 0.4f),
                    shape = MaterialTheme.shapes.medium,
                )
                .padding(Spacing.xl),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.QrCodeScanner,
                    contentDescription = null,
                    tint = BrandTokens.colorCyanPrimary,
                    modifier = Modifier.size(52.dp),
                )
                Spacer(Modifier.height(Spacing.md))
                Text(
                    text = "Scan QR to connect",
                    style = MaterialTheme.typography.titleMedium,
                    color = BrandTokens.colorCyanPrimary,
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = "Open your gateway's connect page in a browser:\nhttp://<gateway-ip>:19999",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                    color = BrandTokens.colorTextSecondary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(Spacing.md))
                Button(
                    onClick = onScanQr,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrandTokens.colorCyanPrimary,
                        contentColor = BrandTokens.colorAbyss,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Text("Open Camera")
                }
            }
        }

        Spacer(Modifier.height(Spacing.md))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            OutlinedButton(onClick = onScanLan, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text("Scan LAN")
            }
            OutlinedButton(onClick = onManualEntry, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text("Manual")
            }
        }
    }
}
