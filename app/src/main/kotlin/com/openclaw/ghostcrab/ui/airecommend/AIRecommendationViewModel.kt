package com.openclaw.ghostcrab.ui.airecommend

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openclaw.ghostcrab.domain.exception.AIQuotaExceededException
import com.openclaw.ghostcrab.domain.exception.AIServiceUnavailableException
import com.openclaw.ghostcrab.domain.exception.GatewayException
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import com.openclaw.ghostcrab.domain.model.OpenClawConfig
import com.openclaw.ghostcrab.domain.model.RecommendationContext
import com.openclaw.ghostcrab.domain.model.SkillInstallError
import com.openclaw.ghostcrab.domain.model.SkillInstallProgress
import com.openclaw.ghostcrab.domain.model.SuggestedChange
import com.openclaw.ghostcrab.domain.repository.AIRecommendationService
import com.openclaw.ghostcrab.domain.repository.ConfigRepository
import com.openclaw.ghostcrab.domain.repository.GatewayConnectionManager
import com.openclaw.ghostcrab.domain.repository.InstalledSkillRepository
import com.openclaw.ghostcrab.domain.repository.ModelRepository
import com.openclaw.ghostcrab.domain.repository.ScopeProbe
import com.openclaw.ghostcrab.domain.repository.ScopeProbeResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * ViewModel for the AI Recommendations screen.
 *
 * On initialization, checks whether the connected gateway advertises the `skill-ai-recommend`
 * capability. Exposes [state] as an immutable [StateFlow] of [AIRecommendationUiState].
 *
 * Context for each query is auto-collected from [configRepository] and [modelRepository] —
 * the user is never asked to supply it manually.
 *
 * @param aiService Gateway-proxied AI recommendation service.
 * @param connectionManager Provides live connection state.
 * @param configRepository Used to read the current gateway config when building query context.
 * @param modelRepository Used to identify the active model when building query context.
 * @param scopeProbe Optional — probes token scopes to surface scope-specific install copy.
 *   Null in release builds (not registered in Koin).
 * @param installRepo Optional — in-app skill installer. Null when SKILLS_INSTALL_ENABLED is off.
 *   Presence drives the `canInstall` flag on [AIRecommendationUiState.SkillUnavailable].
 */
class AIRecommendationViewModel(
    private val aiService: AIRecommendationService,
    private val connectionManager: GatewayConnectionManager,
    private val configRepository: ConfigRepository,
    private val modelRepository: ModelRepository,
    private val scopeProbe: ScopeProbe? = null,
    private val installRepo: InstalledSkillRepository? = null,
) : ViewModel() {

    private val _state = MutableStateFlow<AIRecommendationUiState>(AIRecommendationUiState.Idle)

    /** Observable UI state. */
    val state: StateFlow<AIRecommendationUiState> = _state.asStateFlow()

    /** Remembered so we can auto-resubmit after a successful install. Empty when never submitted. */
    private var lastQuery: String = ""

    init {
        viewModelScope.launch {
            // StateFlow already deduplicates by value (operator fusion) — collect directly
            connectionManager.connectionState.collect { conn ->
                // Explicit arms — sealed interface forces exhaustiveness when used as expression.
                // Using `when` as statement here, so name every arm for clarity.
                when (conn) {
                    is GatewayConnection.Connected -> checkAvailability()
                    is GatewayConnection.Disconnected -> Unit // Screen handles back navigation
                    is GatewayConnection.Connecting -> Unit  // No action during handshake
                    is GatewayConnection.Error -> Unit       // Phase 9: surface disconnection state to screen
                }
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Submits [query] to the gateway AI skill.
     *
     * Transitions: [AIRecommendationUiState.Idle] / [AIRecommendationUiState.Ready] →
     * [AIRecommendationUiState.Loading] → [AIRecommendationUiState.Ready] |
     * [AIRecommendationUiState.Error] | [AIRecommendationUiState.SkillUnavailable]
     *
     * @param query Non-blank user query string.
     */
    fun submitQuery(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        lastQuery = trimmed
        viewModelScope.launch {
            _state.value = AIRecommendationUiState.Loading
            try {
                val context = buildContext()
                val recommendation = aiService.getRecommendation(trimmed, context)
                _state.value = AIRecommendationUiState.Ready(
                    query = trimmed,
                    recommendation = recommendation,
                    selectedChanges = recommendation.suggestedChanges.toSet(),
                )
            } catch (e: AIServiceUnavailableException) {
                val missingScope = if (scopeProbe != null) {
                    when (val r = scopeProbe.probe()) {
                        is ScopeProbeResult.Known -> if (r.has("operator.admin")) null else "operator.admin"
                        ScopeProbeResult.UnknownOldGateway -> null
                        is ScopeProbeResult.Failed -> null
                    }
                } else null
                _state.value = AIRecommendationUiState.SkillUnavailable(
                    missingScope = missingScope,
                    canInstall = installRepo != null && missingScope == null,
                )
            } catch (e: AIQuotaExceededException) {
                _state.value = AIRecommendationUiState.Error(
                    "AI rate limit exceeded at ${e.url}. Please wait before retrying.",
                )
            } catch (e: GatewayException) {
                _state.value = AIRecommendationUiState.Error(e.message ?: "Unknown gateway error")
            }
        }
    }

    /**
     * Toggles [change] in or out of [AIRecommendationUiState.Ready.selectedChanges].
     *
     * No-op if the current state is not [AIRecommendationUiState.Ready].
     *
     * @param change The [SuggestedChange] to toggle.
     */
    fun toggleChange(change: SuggestedChange) {
        _state.update { state ->
            if (state !is AIRecommendationUiState.Ready) return@update state
            val updated = if (change in state.selectedChanges) {
                state.selectedChanges - change
            } else {
                state.selectedChanges + change
            }
            state.copy(selectedChanges = updated)
        }
    }

    /**
     * Applies all changes in [AIRecommendationUiState.Ready.selectedChanges] via [configRepository].
     *
     * Each change is sent as a JSON merge-patch: `{key: suggestedValue}` to `section`.
     * Sets [AIRecommendationUiState.Ready.applySuccess] on success, or
     * [AIRecommendationUiState.Ready.applyError] on failure.
     *
     * Re-reads the current state snapshot immediately before the final assignment so that any
     * toggles made while the apply coroutine was in flight are preserved.
     *
     * No-op if state is not [AIRecommendationUiState.Ready] or [selectedChanges] is empty.
     */
    fun applySelectedChanges() {
        val snapshot = _state.value as? AIRecommendationUiState.Ready ?: return
        if (snapshot.selectedChanges.isEmpty()) return
        viewModelScope.launch {
            _state.update { (it as? AIRecommendationUiState.Ready)?.copy(isApplying = true, applyError = null) ?: it }
            var appliedCount = 0
            try {
                snapshot.selectedChanges.forEach { change ->
                    val patch = buildJsonObject { put(change.key, parseJsonValue(change.suggestedValue)) }
                    configRepository.updateConfig(change.section, patch)
                    appliedCount++
                }
                // Re-read state so any toggles made mid-apply aren't clobbered
                _state.update {
                    (it as? AIRecommendationUiState.Ready)
                        ?.copy(isApplying = false, applySuccess = true) ?: it
                }
            } catch (e: GatewayException) {
                val total = snapshot.selectedChanges.size
                val errorMsg = if (appliedCount > 0) {
                    "Applied $appliedCount of $total changes; " +
                        "stopped at change ${appliedCount + 1}: " +
                        (e.message ?: "Unknown error")
                } else {
                    e.message ?: "Failed to apply changes"
                }
                _state.update {
                    (it as? AIRecommendationUiState.Ready)
                        ?.copy(isApplying = false, applyError = errorMsg) ?: it
                }
            }
        }
    }

    /**
     * Clears apply-success / apply-error state after the snackbar has been shown.
     *
     * @param clearSuccess If true, resets [AIRecommendationUiState.Ready.applySuccess] to false.
     * @param clearError If true, resets [AIRecommendationUiState.Ready.applyError] to null.
     */
    fun clearApplyFlags(clearSuccess: Boolean = false, clearError: Boolean = false) {
        _state.update { current ->
            val ready = current as? AIRecommendationUiState.Ready ?: return@update current
            ready.copy(
                applySuccess = if (clearSuccess) false else ready.applySuccess,
                applyError = if (clearError) null else ready.applyError,
            )
        }
    }

    /**
     * Resets state to [AIRecommendationUiState.Idle] so the user can submit a new query.
     */
    fun reset() {
        _state.value = AIRecommendationUiState.Idle
    }

    /**
     * Installs the AI-recommend skill from ClawHub and — on success — auto-resubmits the
     * last query (or transitions to [AIRecommendationUiState.Idle] if none was made yet).
     *
     * No-op unless current state is [AIRecommendationUiState.SkillUnavailable] with
     * [AIRecommendationUiState.SkillUnavailable.canInstall] true and no install already
     * in flight. If [installRepo] is null the call is silently ignored.
     *
     * On terminal failure, stores the error on the state; call [dismissInstallError] to clear.
     * If the error is [SkillInstallError.Unauthorized] the `missingScope` is populated and
     * `canInstall` flipped false so the Install button disappears.
     */
    fun installSkill() {
        val repo = installRepo ?: return
        val current = (_state.value as? AIRecommendationUiState.SkillUnavailable)
            ?.takeIf { it.canInstall && it.installProgress == null } ?: return

        viewModelScope.launch {
            repo.install(AI_RECOMMEND_SLUG).collect { progress ->
                when (progress) {
                    is SkillInstallProgress.Succeeded -> {
                        if (lastQuery.isNotEmpty()) {
                            submitQuery(lastQuery)
                        } else {
                            _state.value = AIRecommendationUiState.Idle
                        }
                    }
                    is SkillInstallProgress.Failed -> {
                        val err = progress.error
                        val scopeFromErr = (err as? SkillInstallError.Unauthorized)?.missingScope
                        _state.update {
                            (it as? AIRecommendationUiState.SkillUnavailable)?.copy(
                                installProgress = null,
                                installError = err,
                                missingScope = scopeFromErr ?: it.missingScope,
                                canInstall = scopeFromErr == null && it.canInstall,
                            ) ?: it
                        }
                    }
                    else -> {
                        _state.update {
                            (it as? AIRecommendationUiState.SkillUnavailable)
                                ?.copy(installProgress = progress) ?: it
                        }
                    }
                }
            }
        }
    }

    /** Clears [AIRecommendationUiState.SkillUnavailable.installError] after the user dismisses it. */
    fun dismissInstallError() {
        _state.update {
            (it as? AIRecommendationUiState.SkillUnavailable)?.copy(installError = null) ?: it
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun checkAvailability() {
        if (!aiService.isAvailable()) {
            // Only set SkillUnavailable if we aren't mid-query (preserves Loading/Ready states)
            if (_state.value is AIRecommendationUiState.Idle ||
                _state.value is AIRecommendationUiState.SkillUnavailable
            ) {
                _state.value = AIRecommendationUiState.SkillUnavailable(
                    canInstall = installRepo != null,
                )
            }
        } else if (_state.value is AIRecommendationUiState.SkillUnavailable) {
            _state.value = AIRecommendationUiState.Idle
        }
    }

    /**
     * Assembles context for the AI query. Network failures for config/model are non-fatal —
     * the request proceeds with empty config or null model rather than aborting.
     *
     * [CancellationException] is explicitly re-thrown so coroutine cancellation is never swallowed.
     */
    private suspend fun buildContext(): RecommendationContext {
        val connState = connectionManager.connectionState.value as? GatewayConnection.Connected
        val activeConfig = runCatching { configRepository.getConfig() }
            .onFailure { if (it is CancellationException) throw it }
            .getOrElse { OpenClawConfig(emptyMap()) }
        val activeModelId = runCatching {
            modelRepository.getModels().firstOrNull { it.isActive }?.id
        }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
        return RecommendationContext(
            activeConfig = activeConfig,
            hardwareInfo = connState?.hardwareInfo,
            activeModelId = activeModelId,
        )
    }

    /**
     * Parses [value] as a [JsonElement]. Falls back to a [JsonPrimitive] string if parsing fails,
     * so malformed suggestions don't crash the apply flow.
     *
     * [CancellationException] cannot be thrown here (no suspending calls).
     *
     * @param value JSON-encoded string from the AI response.
     * @return Parsed [JsonElement], or a string primitive on parse failure.
     */
    private fun parseJsonValue(value: String): JsonElement = runCatching {
        Json.parseToJsonElement(value)
    }.getOrElse {
        JsonPrimitive(value)
    }

    private companion object {
        /** ClawHub slug for the AI recommendation skill. Must match strings.xml copy. */
        const val AI_RECOMMEND_SLUG = "wanng-ide/auto-skill-hunter"
    }
}
