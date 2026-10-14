package com.openclaw.ghostcrab.ui.model

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openclaw.ghostcrab.R
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.Spacing
import org.koin.androidx.compose.koinViewModel

/**
 * Model Manager screen — displays all models registered on the connected gateway
 * and allows the user to swap the active model.
 *
 * @param onNavigateBack Called to pop the back stack (used on [ModelManagerUiState.Disconnected]).
 * @param viewModel Injected via Koin by default.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun ModelManagerScreen(
    onNavigateBack: () -> Unit,
    viewModel: ModelManagerViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

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

                is ModelManagerUiState.NotSupported -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.model_manager_not_supported),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = BrandTokens.colorTextSecondary,
                            ),
                            modifier = Modifier.padding(Spacing.md),
                        )
                    }
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
                    ModelManagerReadyContent(
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
