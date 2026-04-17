package com.openclaw.ghostcrab.ui.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
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

    LaunchedEffect(prefillUrl) {
        if (prefillUrl != null) viewModel.setPrefillUrl(prefillUrl)
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                ManualEntryEvent.NavigateToDashboard -> onNavigateToDashboard()
            }
        }
    }

    val isConnecting = uiState is ManualEntryUiState.Connecting
    val errorMessage = (uiState as? ManualEntryUiState.Error)?.message
    val showHttpBanner = !form.useHttps

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

            // HTTPS toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        "Use HTTPS",
                        style = MaterialTheme.typography.bodyMedium,
                        color = BrandTokens.colorTextPrimary,
                    )
                    Text(
                        if (form.useHttps) "https://" else "http://",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                        color = BrandTokens.colorTextSecondary,
                    )
                }
                Switch(
                    checked = form.useHttps,
                    onCheckedChange = viewModel::onHttpsToggle,
                    enabled = !isConnecting,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = BrandTokens.colorAbyss,
                        checkedTrackColor = BrandTokens.colorCyanPrimary,
                        uncheckedThumbColor = BrandTokens.colorTextSecondary,
                        uncheckedTrackColor = BrandTokens.colorOutline,
                    ),
                )
            }

            Spacer(Modifier.height(Spacing.md))

            // Host + Port row
            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = form.host,
                    onValueChange = viewModel::onHostChange,
                    label = { Text("Host / IP") },
                    placeholder = {
                        Text(
                            "192.168.0.23",
                            fontFamily = MonoFontFamily,
                            color = BrandTokens.colorTextDisabled,
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFontFamily),
                    isError = form.hostError != null,
                    supportingText = {
                        if (form.hostError != null) {
                            Text(form.hostError!!, color = BrandTokens.colorCrimsonError)
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                    singleLine = true,
                    enabled = !isConnecting,
                    modifier = Modifier.weight(1f),
                    colors = fieldColors(),
                )

                Spacer(Modifier.width(Spacing.sm))

                OutlinedTextField(
                    value = form.port,
                    onValueChange = viewModel::onPortChange,
                    label = { Text("Port") },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFontFamily),
                    isError = form.portError != null,
                    supportingText = {
                        if (form.portError != null) {
                            Text(form.portError!!, color = BrandTokens.colorCrimsonError)
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next,
                    ),
                    singleLine = true,
                    enabled = !isConnecting,
                    modifier = Modifier.width(120.dp),
                    colors = fieldColors(),
                )
            }

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

            Spacer(Modifier.height(Spacing.md))

            // Assembled URL preview — mono-spaced, shows exactly what will be hit
            Text(
                text = form.assembledUrl,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                color = BrandTokens.colorTextSecondary,
            )

            Spacer(Modifier.height(Spacing.lg))

            if (errorMessage != null) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                    color = BrandTokens.colorCrimsonError,
                )
                Spacer(Modifier.height(Spacing.md))
            }

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
