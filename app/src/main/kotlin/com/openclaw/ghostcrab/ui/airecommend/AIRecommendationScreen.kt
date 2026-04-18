package com.openclaw.ghostcrab.ui.airecommend

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openclaw.ghostcrab.R
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import com.openclaw.ghostcrab.ui.components.CodeBlock
import com.openclaw.ghostcrab.ui.components.GlassSurface
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.MonoFontFamily
import com.openclaw.ghostcrab.ui.theme.Spacing
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/**
 * AI Recommendations screen.
 *
 * Presents a free-text query field. On submit, sends the query plus auto-collected context
 * (active config, active model, hardware info) to the gateway AI skill. If the skill is absent,
 * shows an empty state with install guidance.
 *
 * @param onNavigateBack Called to pop back to Dashboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIRecommendationScreen(onNavigateBack: () -> Unit) {
    val viewModel: AIRecommendationViewModel = koinViewModel()
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var query by rememberSaveable { mutableStateOf("") }
    // rememberSaveable so sheet visibility survives configuration changes
    var showApplySheet by rememberSaveable { mutableStateOf(false) }

    val applySuccessMessage = stringResource(R.string.ai_apply_success)

    // Stable derivation — only recomputed when state identity changes (not a delegated property so smart-cast works)
    val readyState = remember(state) { state as? AIRecommendationUiState.Ready }

    LaunchedEffect(readyState?.applySuccess) {
        if (readyState?.applySuccess == true) {
            showApplySheet = false
            snackbarHostState.showSnackbar(applySuccessMessage)
            viewModel.clearApplySuccess()
        }
    }

    LaunchedEffect(readyState?.applyError) {
        val error = readyState?.applyError
        if (error != null) {
            snackbarHostState.showSnackbar(error)
            viewModel.clearApplyError()
        }
    }

    Scaffold(
        containerColor = BrandTokens.colorAbyss,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.ai_screen_title),
                        style = MaterialTheme.typography.titleLarge.copy(
                            color = BrandTokens.colorTextPrimary,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.ai_back_cd),
                            tint = BrandTokens.colorTextPrimary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BrandTokens.colorAbyss),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = Spacing.md),
        ) {
            Spacer(Modifier.height(Spacing.sm))

            // Query input hidden when skill missing — user must install the skill first
            if (state !is AIRecommendationUiState.SkillUnavailable) {
                QuerySection(
                    query = query,
                    onQueryChange = { query = it },
                    isLoading = state is AIRecommendationUiState.Loading,
                    onSubmit = { viewModel.submitQuery(query) },
                )

                Spacer(Modifier.height(Spacing.md))
            }

            // Main content area
            Box(modifier = Modifier.weight(1f)) {
                when (val s = state) {
                    is AIRecommendationUiState.Idle -> {
                        IdleContent()
                    }

                    is AIRecommendationUiState.Loading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            color = BrandTokens.colorCyanPrimary,
                        )
                    }

                    is AIRecommendationUiState.SkillUnavailable -> {
                        val copiedMessage = stringResource(R.string.ai_skill_unavailable_copied)
                        val scope = rememberCoroutineScope()
                        SkillUnavailableContent(
                            onCopied = {
                                scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                            },
                        )
                    }

                    is AIRecommendationUiState.Error -> {
                        ErrorContent(
                            message = s.message,
                            onRetry = { viewModel.submitQuery(query) },
                        )
                    }

                    is AIRecommendationUiState.Ready -> {
                        ReadyContent(
                            state = s,
                            onNewQuery = {
                                query = ""
                                viewModel.reset()
                            },
                            onApplySuggestions = { showApplySheet = true },
                        )
                    }
                }
            }
        }

        // Apply suggestions sheet
        if (showApplySheet && readyState != null) {
            val allChanges = readyState.recommendation.suggestedChanges
            if (allChanges.isNotEmpty()) {
                ApplySuggestionsSheet(
                    changes = allChanges,
                    selectedChanges = readyState.selectedChanges,
                    isApplying = readyState.isApplying,
                    onToggle = { viewModel.toggleChange(it) },
                    onApply = { viewModel.applySelectedChanges() },
                    onDismiss = { showApplySheet = false },
                )
            }
        }
    }
}

// ── Query section ──────────────────────────────────────────────────────────────

@Composable
private fun QuerySection(
    query: String,
    onQueryChange: (String) -> Unit,
    isLoading: Boolean,
    onSubmit: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    text = stringResource(R.string.ai_query_hint),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = BrandTokens.colorTextDisabled,
                    ),
                )
            },
            minLines = 3,
            maxLines = 6,
            enabled = !isLoading,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = BrandTokens.colorTextPrimary,
                unfocusedTextColor = BrandTokens.colorTextPrimary,
                focusedBorderColor = BrandTokens.colorCyanPrimary,
                unfocusedBorderColor = BrandTokens.colorOutline,
                disabledBorderColor = BrandTokens.colorOutline.copy(alpha = 0.4f),
                cursorColor = BrandTokens.colorCyanPrimary,
            ),
        )

        Spacer(Modifier.height(Spacing.sm))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(
                onClick = onSubmit,
                enabled = query.isNotBlank() && !isLoading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandTokens.colorCyanPrimary,
                    contentColor = BrandTokens.colorAbyss,
                    disabledContainerColor = BrandTokens.colorCyanPrimary.copy(alpha = 0.35f),
                    disabledContentColor = BrandTokens.colorAbyss.copy(alpha = 0.5f),
                ),
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(Spacing.xs))
                Text(
                    text = stringResource(R.string.ai_ask_button),
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

// ── Idle ──────────────────────────────────────────────────────────────────────

@Composable
private fun IdleContent() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(Spacing.xl),
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = BrandTokens.colorCyanPrimary.copy(alpha = 0.4f),
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = stringResource(R.string.ai_idle_hint),
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = BrandTokens.colorTextSecondary,
                ),
            )
        }
    }
}

// ── Skill unavailable ─────────────────────────────────────────────────────────

@Composable
private fun SkillUnavailableContent(onCopied: () -> Unit) {
    val context = LocalContext.current
    val clawhubUrl = stringResource(R.string.ai_skill_unavailable_clawhub_url)
    val installCommand = stringResource(R.string.ai_skill_unavailable_hint)

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        GlassSurface(modifier = Modifier.padding(Spacing.md)) {
            Column(
                modifier = Modifier.padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    text = stringResource(R.string.ai_skill_unavailable_title),
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = BrandTokens.colorAmberWarn,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Text(
                    text = stringResource(R.string.ai_skill_unavailable_body),
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = BrandTokens.colorTextSecondary,
                    ),
                )
                Spacer(Modifier.height(Spacing.xs))
                CodeBlock(
                    code = installCommand,
                    onCopied = onCopied,
                )
                Spacer(Modifier.height(Spacing.xs))
                OutlinedButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(clawhubUrl))
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Default.OpenInBrowser,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = BrandTokens.colorCyanPrimary,
                    )
                    Spacer(Modifier.size(Spacing.xs))
                    Text(
                        text = stringResource(R.string.ai_skill_unavailable_open_browser),
                        color = BrandTokens.colorCyanPrimary,
                    )
                }
            }
        }
    }
}

// ── Error ─────────────────────────────────────────────────────────────────────

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = BrandTokens.colorCrimsonError,
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
                    text = stringResource(R.string.ai_retry_button),
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

// ── Ready ─────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReadyContent(
    state: AIRecommendationUiState.Ready,
    onNewQuery: () -> Unit,
    onApplySuggestions: () -> Unit,
) {
    val changes = state.recommendation.suggestedChanges
    Column(modifier = Modifier.fillMaxSize()) {
        // Recommendation text
        GlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(Spacing.md),
            ) {
                Text(
                    text = stringResource(R.string.ai_recommendations_label),
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = BrandTokens.colorCyanPrimary,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = state.recommendation.recommendation,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = BrandTokens.colorTextPrimary,
                    ),
                )

                // Suggested change summary chips
                if (changes.isNotEmpty()) {
                    Spacer(Modifier.height(Spacing.md))
                    Text(
                        text = stringResource(R.string.ai_suggestions_count, changes.size),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = BrandTokens.colorTextSecondary,
                        ),
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        changes.forEach { change ->
                            SuggestionChip(
                                onClick = {},
                                label = {
                                    Text(
                                        text = "${change.section}.${change.key}",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = MonoFontFamily,
                                            color = BrandTokens.colorCyanPulse,
                                        ),
                                    )
                                },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = BrandTokens.colorGlass,
                                ),
                                border = SuggestionChipDefaults.suggestionChipBorder(
                                    enabled = true,
                                    borderColor = BrandTokens.colorCyanPrimary.copy(alpha = 0.4f),
                                ),
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(Spacing.sm))

        // Action row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End),
        ) {
            TextButton(onClick = onNewQuery) {
                Text(
                    text = stringResource(R.string.ai_new_query),
                    color = BrandTokens.colorTextSecondary,
                )
            }

            if (changes.isNotEmpty()) {
                Button(
                    onClick = onApplySuggestions,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrandTokens.colorCyanPrimary,
                        contentColor = BrandTokens.colorAbyss,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.ai_apply_suggestions),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        Spacer(Modifier.height(Spacing.md))
    }
}
