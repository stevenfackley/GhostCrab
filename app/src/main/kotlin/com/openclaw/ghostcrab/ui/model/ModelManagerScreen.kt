package com.openclaw.ghostcrab.ui.model

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openclaw.ghostcrab.R
import com.openclaw.ghostcrab.domain.model.ModelInfo
import com.openclaw.ghostcrab.ui.components.GlassSurface
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.MonoFontFamily
import com.openclaw.ghostcrab.ui.theme.Spacing
import org.koin.androidx.compose.koinViewModel

/**
 * Model Manager screen — displays all models registered on the connected gateway
 * and allows the user to swap the active model.
 *
 * @param onNavigateBack Called to pop the back stack (used on [ModelManagerUiState.Disconnected]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun ModelManagerScreen(onNavigateBack: () -> Unit) {
    val viewModel: ModelManagerViewModel = koinViewModel()
    val state by viewModel.state.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val swapSuccessMessage = stringResource(R.string.model_swap_success)

    // Navigate back when disconnected
    LaunchedEffect(state) {
        if (state is ModelManagerUiState.Disconnected) {
            onNavigateBack()
        }
    }

    val readyState = state as? ModelManagerUiState.Ready

    // Show snackbar on swap success
    LaunchedEffect(readyState?.swapSuccess) {
        if (readyState?.swapSuccess == true) {
            snackbarHostState.showSnackbar(swapSuccessMessage)
            viewModel.clearSwapSuccess()
        }
    }

    // Show snackbar on swap failure
    LaunchedEffect(readyState?.swapError) {
        val error = readyState?.swapError
        if (error != null) {
            snackbarHostState.showSnackbar(error)
            viewModel.clearSwapError()
        }
    }

    Scaffold(
        containerColor = BrandTokens.colorAbyss,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.model_manager_title),
                        style = MaterialTheme.typography.titleLarge.copy(
                            color = BrandTokens.colorTextPrimary,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.model_manager_back_cd),
                            tint = BrandTokens.colorTextPrimary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BrandTokens.colorAbyss,
                ),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (val s = state) {
                is ModelManagerUiState.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = BrandTokens.colorCyanPrimary,
                    )
                }

                is ModelManagerUiState.Disconnected -> {
                    // LaunchedEffect handles navigation; render nothing
                }

                is ModelManagerUiState.Error -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(Spacing.md),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        Text(
                            text = s.message,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = BrandTokens.colorCrimsonError,
                            ),
                        )
                        Button(
                            onClick = { viewModel.loadModels() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BrandTokens.colorCyanPrimary,
                                contentColor = BrandTokens.colorAbyss,
                            ),
                        ) {
                            Text(
                                text = stringResource(R.string.model_manager_retry),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }

                is ModelManagerUiState.Ready -> {
                    ReadyContent(
                        state = s,
                        onRetry = { viewModel.loadModels() },
                        onRequestSwap = { viewModel.requestSwap(it) },
                        onCancelSwap = { viewModel.cancelSwap() },
                        onConfirmSwap = { viewModel.confirmSwap(it) },
                    )
                }
            }
        }
    }
}

// ── Ready content ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReadyContent(
    state: ModelManagerUiState.Ready,
    onRetry: () -> Unit,
    onRequestSwap: (String) -> Unit,
    onCancelSwap: () -> Unit,
    onConfirmSwap: (String) -> Unit,
) {
    var selectedModel by remember { mutableStateOf<ModelInfo?>(null) }

    if (state.models.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Text(
                    text = stringResource(R.string.model_manager_empty),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = BrandTokens.colorTextSecondary,
                    ),
                )
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrandTokens.colorCyanPrimary,
                        contentColor = BrandTokens.colorAbyss,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.model_manager_retry),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item { Spacer(Modifier.height(Spacing.xs)) }
            items(state.models, key = { it.id }) { model ->
                ModelCard(
                    model = model,
                    isSwapping = state.isSwapping,
                    onClick = { selectedModel = model },
                    onSetActive = { onRequestSwap(model.id) },
                )
            }
            item { Spacer(Modifier.height(Spacing.md)) }
        }
    }

    // Swap confirmation dialog
    val pendingId = state.pendingSwapId
    if (pendingId != null) {
        AlertDialog(
            onDismissRequest = onCancelSwap,
            title = {
                Text(
                    text = stringResource(R.string.model_swap_dialog_title),
                    color = BrandTokens.colorTextPrimary,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.model_swap_dialog_body),
                    color = BrandTokens.colorTextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = { onConfirmSwap(pendingId) }) {
                    Text(
                        text = stringResource(R.string.model_swap_dialog_confirm),
                        color = BrandTokens.colorCyanPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelSwap) {
                    Text(
                        text = stringResource(R.string.model_swap_dialog_cancel),
                        color = BrandTokens.colorTextSecondary,
                    )
                }
            },
            containerColor = BrandTokens.colorAbyssRaised,
        )
    }

    // Model detail bottom sheet
    val shown = selectedModel
    if (shown != null) {
        ModelDetailSheet(
            model = shown,
            onSetActive = {
                selectedModel = null
                onRequestSwap(shown.id)
            },
            onDismiss = { selectedModel = null },
        )
    }
}

// ── Model card ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelCard(
    model: ModelInfo,
    isSwapping: Boolean,
    onClick: () -> Unit,
    onSetActive: () -> Unit,
) {
    val activeBorder = if (model.isActive) {
        Modifier.border(
            border = BorderStroke(1.dp, BrandTokens.colorCyanPrimary),
            shape = MaterialTheme.shapes.medium,
        )
    } else {
        Modifier
    }

    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .then(activeBorder)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Top row: displayName + status/active pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = model.displayName,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = BrandTokens.colorTextPrimary,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(model = model)
            }

            Spacer(modifier = Modifier.height(Spacing.xs))

            // Provider · id in mono
            Text(
                text = "${model.provider} · ${model.id}",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = MonoFontFamily,
                    color = BrandTokens.colorTextSecondary,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // Capability chips
            if (model.capabilities.isNotEmpty()) {
                Spacer(modifier = Modifier.height(Spacing.xs))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    model.capabilities.forEach { cap ->
                        SuggestionChip(
                            onClick = {},
                            label = {
                                Text(
                                    text = cap,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = BrandTokens.colorTextSecondary,
                                    ),
                                )
                            },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = BrandTokens.colorGlass,
                            ),
                            border = SuggestionChipDefaults.suggestionChipBorder(
                                enabled = true,
                                borderColor = BrandTokens.colorOutline,
                            ),
                        )
                    }
                }
            }

            // Set Active button
            if (!model.isActive) {
                Spacer(modifier = Modifier.height(Spacing.sm))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(
                        onClick = onSetActive,
                        enabled = !isSwapping,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BrandTokens.colorCyanPrimary,
                            contentColor = BrandTokens.colorAbyss,
                            disabledContainerColor = BrandTokens.colorCyanPrimary.copy(alpha = 0.4f),
                            disabledContentColor = BrandTokens.colorAbyss.copy(alpha = 0.5f),
                        ),
                    ) {
                        Text(
                            text = stringResource(R.string.model_set_active),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(model: ModelInfo) {
    if (model.isActive) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = BrandTokens.colorCyanPrimary,
        ) {
            Text(
                text = stringResource(R.string.model_status_active),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = BrandTokens.colorAbyss,
                ),
                modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            )
        }
    } else {
        val (pillColor, textColor) = when (model.status) {
            "auth-error" -> BrandTokens.colorCrimsonError to BrandTokens.colorAbyss
            "loading" -> BrandTokens.colorAmberWarn to BrandTokens.colorAbyss
            else -> BrandTokens.colorGlass to BrandTokens.colorTextSecondary
        }
        Surface(
            shape = MaterialTheme.shapes.small,
            color = pillColor,
        ) {
            Text(
                text = model.status,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = MonoFontFamily,
                    color = textColor,
                ),
                modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            )
        }
    }
}
