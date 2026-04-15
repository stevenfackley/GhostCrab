package com.openclaw.ghostcrab.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.ui.text.style.TextAlign
import com.openclaw.ghostcrab.ui.components.GlassSurface
import com.openclaw.ghostcrab.ui.config.sections.GatewaySectionEditor
import com.openclaw.ghostcrab.ui.config.sections.RawJsonSectionEditor
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.Spacing
import org.koin.androidx.compose.koinViewModel

/**
 * Top-level Config Editor screen.
 *
 * Handles all [ConfigEditorUiState] states:
 * - [ConfigEditorUiState.Loading] — centered progress indicator.
 * - [ConfigEditorUiState.Disconnected] — navigates back via [onNavigateBack].
 * - [ConfigEditorUiState.Error] — exact error message + Retry button.
 * - [ConfigEditorUiState.Ready] — section list with expand/collapse, diff sheet,
 *   save-success snackbar, and concurrent-edit dialog.
 *
 * Section order: `"gateway"` first, remaining keys alphabetically.
 *
 * @param onNavigateBack Called when the back arrow is tapped or state becomes [ConfigEditorUiState.Disconnected].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigEditorScreen(
    onNavigateBack: () -> Unit,
) {
    val viewModel: ConfigEditorViewModel = koinViewModel()
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Navigate back when disconnected
    LaunchedEffect(state) {
        if (state is ConfigEditorUiState.Disconnected) {
            onNavigateBack()
        }
    }

    // Show save-success snackbar
    val readyState = state as? ConfigEditorUiState.Ready
    LaunchedEffect(readyState?.saveSuccess) {
        if (readyState?.saveSuccess == true) {
            snackbarHostState.showSnackbar("Configuration saved")
            viewModel.clearSaveSuccess()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configure Gateway") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BrandTokens.colorAbyss,
                    titleContentColor = BrandTokens.colorTextPrimary,
                    navigationIconContentColor = BrandTokens.colorTextPrimary,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = BrandTokens.colorAbyss,
    ) { innerPadding ->

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (val s = state) {
                is ConfigEditorUiState.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = BrandTokens.colorCyanPrimary,
                    )
                }

                is ConfigEditorUiState.Disconnected -> {
                    // Navigation handled by LaunchedEffect above; show nothing.
                }

                is ConfigEditorUiState.Error -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(Spacing.lg),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        Text(
                            text = s.message,
                            color = BrandTokens.colorCrimsonError,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                        Button(
                            onClick = { viewModel.loadConfig() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BrandTokens.colorCyanPrimary,
                                contentColor = BrandTokens.colorAbyss,
                            ),
                        ) {
                            Text("Retry")
                        }
                    }
                }

                is ConfigEditorUiState.Ready -> {
                    ReadyContent(
                        state = s,
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize(),
                    )

                    // Diff sheet
                    val savingSection = s.pendingSaveSection
                    if (savingSection != null) {
                        val oldValue = s.config.sections[savingSection]
                        val newValue = s.pendingChanges[savingSection]
                        if (oldValue != null && newValue != null) {
                            PendingChangesDiffSheet(
                                sectionKey = savingSection,
                                oldValue = oldValue,
                                newValue = newValue,
                                onConfirm = { viewModel.confirmSave(savingSection) },
                                onDismiss = { viewModel.cancelSave() },
                            )
                        }
                    }

                    // Concurrent-edit dialog
                    val concurrentSection = s.concurrentEditSection
                    if (concurrentSection != null) {
                        AlertDialog(
                            onDismissRequest = { viewModel.dismissConcurrentEdit() },
                            title = { Text("Configuration Changed") },
                            text = {
                                Text(
                                    "Server values changed since you opened section " +
                                        "\"$concurrentSection\". The latest values have been loaded.",
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    viewModel.dismissConcurrentEdit()
                                    viewModel.loadConfig()
                                }) {
                                    Text(
                                        "Reload",
                                        color = BrandTokens.colorCyanPrimary,
                                    )
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { viewModel.dismissConcurrentEdit() }) {
                                    Text("Dismiss")
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadyContent(
    state: ConfigEditorUiState.Ready,
    viewModel: ConfigEditorViewModel,
    modifier: Modifier = Modifier,
) {
    var expandedSections by remember { mutableStateOf(emptySet<String>()) }

    // Section order: "gateway" first, rest alphabetical
    val orderedKeys = remember(state.config.sections) {
        val keys = state.config.sections.keys.toMutableList()
        keys.sortWith(Comparator { a, b ->
            when {
                a == "gateway" -> -1
                b == "gateway" -> 1
                else -> a.compareTo(b)
            }
        })
        keys
    }

    LazyColumn(
        modifier = modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        items(orderedKeys, key = { it }) { sectionKey ->
            val sectionValue = state.config.sections[sectionKey] ?: return@items
            val isExpanded = expandedSections.contains(sectionKey)
            val hasPending = state.pendingChanges.containsKey(sectionKey)
            val currentValue = state.pendingChanges[sectionKey] ?: sectionValue

            SectionCard(
                sectionKey = sectionKey,
                sectionValue = currentValue,
                originalValue = sectionValue,
                isExpanded = isExpanded,
                hasPending = hasPending,
                fieldErrors = state.fieldErrors,
                onToggleExpand = {
                    expandedSections = if (isExpanded) {
                        expandedSections - sectionKey
                    } else {
                        expandedSections + sectionKey
                    }
                },
                onEdit = { newValue -> viewModel.editSection(sectionKey, newValue) },
                onFieldError = { field, err -> viewModel.setFieldError(field, err) },
                onSave = { viewModel.requestSave(sectionKey) },
                onDiscard = { viewModel.discardSection(sectionKey) },
            )
        }
    }
}

@Composable
private fun SectionCard(
    sectionKey: String,
    sectionValue: kotlinx.serialization.json.JsonElement,
    originalValue: kotlinx.serialization.json.JsonElement,
    isExpanded: Boolean,
    hasPending: Boolean,
    fieldErrors: Map<String, String>,
    onToggleExpand: () -> Unit,
    onEdit: (kotlinx.serialization.json.JsonElement) -> Unit,
    onFieldError: (String, String?) -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
) {
    GlassSurface(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = sectionKey,
                        style = MaterialTheme.typography.titleMedium,
                        color = BrandTokens.colorTextPrimary,
                    )
                    if (hasPending) {
                        SuggestionChip(
                            onClick = {},
                            label = { Text("Modified", style = MaterialTheme.typography.labelSmall) },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = BrandTokens.colorAmberWarn.copy(alpha = 0.2f),
                                labelColor = BrandTokens.colorAmberWarn,
                            ),
                        )
                    }
                }
                IconButton(onClick = onToggleExpand) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint = BrandTokens.colorTextSecondary,
                    )
                }
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(Spacing.sm))

                // Section-specific editor
                if (sectionKey == "gateway") {
                    GatewaySectionEditor(
                        sectionValue = sectionValue,
                        fieldErrors = fieldErrors,
                        onEdit = onEdit,
                        onFieldError = onFieldError,
                    )
                } else {
                    RawJsonSectionEditor(
                        sectionKey = sectionKey,
                        sectionValue = sectionValue,
                        onEdit = onEdit,
                    )
                }

                if (hasPending) {
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        TextButton(
                            onClick = onDiscard,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                "Discard",
                                color = BrandTokens.colorTextSecondary,
                            )
                        }
                        Button(
                            onClick = onSave,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BrandTokens.colorCyanPrimary,
                                contentColor = BrandTokens.colorAbyss,
                            ),
                        ) {
                            Text("Save")
                        }
                    }
                }
            }
        }
    }
}
