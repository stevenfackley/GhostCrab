package com.openclaw.ghostcrab.ui.connection

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.openclaw.ghostcrab.ui.components.HttpSecurityBanner
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.MonoFontFamily
import com.openclaw.ghostcrab.ui.theme.Spacing
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualEntryScreen(
    onNavigateBack: () -> Unit,
    onNavigateToDashboard: () -> Unit,
    prefillUrl: String? = null,
    viewModel: ManualEntryViewModel = koinViewModel(),
) {
    val form by viewModel.form.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    // Pre-fill URL when navigating from LAN scan
    LaunchedEffect(prefillUrl) {
        if (prefillUrl != null) viewModel.setPrefillUrl(prefillUrl)
    }

    // Collect one-shot navigation events
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                ManualEntryEvent.NavigateToDashboard -> onNavigateToDashboard()
            }
        }
    }

    val isConnecting = uiState is ManualEntryUiState.Connecting
    val errorMessage = (uiState as? ManualEntryUiState.Error)?.message

    val showHttpBanner = form.url.trim().startsWith("http://", ignoreCase = true)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Connect to Gateway",
                        style = MaterialTheme.typography.titleMedium,
                        color = BrandTokens.colorTextPrimary,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = BrandTokens.colorTextSecondary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BrandTokens.colorAbyss),
            )
        },
        containerColor = BrandTokens.colorAbyss,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = Spacing.md)
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(Spacing.md))

            if (showHttpBanner) {
                HttpSecurityBanner()
                Spacer(Modifier.height(Spacing.md))
            }

            // URL field
            OutlinedTextField(
                value = form.url,
                onValueChange = viewModel::onUrlChange,
                label = { Text("Gateway URL") },
                placeholder = {
                    Text(
                        "http://192.168.1.50:18789",
                        fontFamily = MonoFontFamily,
                        color = BrandTokens.colorTextDisabled,
                    )
                },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFontFamily),
                isError = form.urlError != null,
                supportingText = {
                    if (form.urlError != null) {
                        Text(form.urlError!!, color = BrandTokens.colorCrimsonError)
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
                singleLine = true,
                enabled = !isConnecting,
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors(),
            )

            Spacer(Modifier.height(Spacing.md))

            // Token field
            OutlinedTextField(
                value = form.token,
                onValueChange = viewModel::onTokenChange,
                label = { Text("Bearer Token (optional)") },
                placeholder = {
                    Text(
                        "Leave empty for unauthenticated gateways",
                        color = BrandTokens.colorTextDisabled,
                    )
                },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFontFamily),
                visualTransformation = if (form.tokenVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = viewModel::toggleTokenVisibility) {
                        Icon(
                            imageVector = if (form.tokenVisible) Icons.Default.VisibilityOff
                            else Icons.Default.Visibility,
                            contentDescription = if (form.tokenVisible) "Hide token" else "Show token",
                            tint = BrandTokens.colorTextSecondary,
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { viewModel.connect() }),
                singleLine = true,
                enabled = !isConnecting,
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors(),
            )

            Spacer(Modifier.height(Spacing.lg))

            // Error message — verbatim from exception per brand voice rules
            if (errorMessage != null) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                    color = BrandTokens.colorCrimsonError,
                )
                Spacer(Modifier.height(Spacing.md))
            }

            // Connect button
            Button(
                onClick = viewModel::connect,
                enabled = !isConnecting,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandTokens.colorCyanPrimary,
                    contentColor = BrandTokens.colorAbyss,
                    disabledContainerColor = BrandTokens.colorCyanPrimary.copy(alpha = 0.4f),
                ),
            ) {
                if (isConnecting) {
                    CircularProgressIndicator(
                        color = BrandTokens.colorAbyss,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Connect", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = BrandTokens.colorCyanPrimary,
    unfocusedBorderColor = BrandTokens.colorOutline,
    focusedLabelColor = BrandTokens.colorCyanPrimary,
    unfocusedLabelColor = BrandTokens.colorTextSecondary,
    cursorColor = BrandTokens.colorCyanPrimary,
    focusedTextColor = BrandTokens.colorTextPrimary,
    unfocusedTextColor = BrandTokens.colorTextPrimary,
    errorBorderColor = BrandTokens.colorCrimsonError,
    errorLabelColor = BrandTokens.colorCrimsonError,
)
