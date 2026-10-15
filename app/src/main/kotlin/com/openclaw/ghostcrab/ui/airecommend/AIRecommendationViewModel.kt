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
import com.openclaw.ghostcrab.domain.repository.ModelRepository
import com.openclaw.ghostcrab.domain.repository.ScopeProbe
import com.openclaw.ghostcrab.domain.repository.ScopeProbeResult
import com.openclaw.ghostcrab.domain.util.suspendRunCatching
import com.openclaw.ghostcrab.ui.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for the AI Recommendations screen.
 *
 * On initialization, asks [aiService] whether recommendations are available. OpenClaw gateways have no
 * recommendation API (ADR-0007), so today they never are and the screen explains that; the whole screen is hidden
 * behind `BuildConfig.AI_PRO_ENABLED`. Exposes [state] as an immutable [StateFlow] of [AIRecommendationUiState].
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
 * @param recommendSkill A gateway skill that serves recommendations plus the installer for it, or `null`. No skill
 *   does today (ADR-0007: a `chat.send`-based design needs its own ADR), so the app passes none. Install is offered,
 *   and a missing `operator.admin` reported, only when it is set; it drives the `canInstall` flag on
 *   [AIRecommendationUiState.SkillUnavailable].
 */
class AIRecommendationViewModel(
    private val aiService: AIRecommendationService,
    private val connectionManager: GatewayConnectionManager,
    private val configRepository: ConfigRepository,
    private val modelRepository: ModelRepository,
    private val scopeProbe: ScopeProbe? = null,
    private val recommendSkill: RecommendSkillInstall? = null,
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
                val context = buildRecommendationContext(connectionManager, configRepository, modelRepository)
                val recommendation = aiService.getRecommendation(trimmed, context)
                // Never offer a suggestion that would write the redaction placeholder back.
                val applicable = recommendation.suggestedChanges.filter { it.isApplicable }
                _state.value = AIRecommendationUiState.Ready(
                    query = trimmed,
                    recommendation = recommendation.copy(suggestedChanges = applicable),
                    selectedChanges = emptySet(),
                    droppedSuggestionCount = recommendation.suggestedChanges.size - applicable.size,
                )
            } catch (e: AIServiceUnavailableException) {
                // A scope gap only matters when there is a skill to install; otherwise it would send the user on
                // an errand that cannot make recommendations appear.
                val missingScope = if (scopeProbe != null && recommendSkill != null) {
                    when (val r = scopeProbe.probe()) {
                        is ScopeProbeResult.Known -> if (r.has("operator.admin")) null else "operator.admin"
                        ScopeProbeResult.UnknownOldGateway -> null
                        is ScopeProbeResult.Failed -> null
                    }
                } else null
                _state.value = AIRecommendationUiState.SkillUnavailable(
                    missingScope = missingScope,
                    canInstall = recommendSkill != null && missingScope == null,
                )
            } catch (e: AIQuotaExceededException) {
                _state.value = AIRecommendationUiState.Error(
                    "AI rate limit reached — wait before retrying. ${e.toUserMessage()}",
                )
            } catch (e: GatewayException) {
                _state.value = AIRecommendationUiState.Error(e.toUserMessage())
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
     * Applies [AIRecommendationUiState.Ready.selectedChanges] via [configRepository].
     *
     * If any selected change is security-affecting and [securityConfirmed] is `false`, nothing is
     * written: [AIRecommendationUiState.Ready.confirmSecurityChanges] is set so the screen can ask
     * first. Call again with `securityConfirmed = true` after the user accepts.
     *
     * Suggestions that echo the redaction placeholder ([isApplicable] is `false`) are never written.
     *
     * No-op if state is not [AIRecommendationUiState.Ready], an apply is already in flight
     * (guards a double tap), or nothing applicable is selected.
     *
     * @param securityConfirmed `true` once the user explicitly accepted security-affecting changes.
     */
    fun applySelectedChanges(securityConfirmed: Boolean = false) {
        val snapshot = (_state.value as? AIRecommendationUiState.Ready)?.takeUnless { it.isApplying } ?: return
        val changes = snapshot.selectedChanges.filter { it.isApplicable }.takeIf { it.isNotEmpty() } ?: return
        val risky = changes.filter { it.isSecurityAffecting }
        if (risky.isNotEmpty() && !securityConfirmed) {
            _state.value = snapshot.copy(confirmSecurityChanges = risky)
        } else {
            launchApply(changes)
        }
    }

    /** Closes the security confirmation without applying anything. */
    fun dismissSecurityConfirmation() {
        _state.update { (it as? AIRecommendationUiState.Ready)?.copy(confirmSecurityChanges = emptyList()) ?: it }
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
     * Installs the recommendation skill ([recommendSkill]) from ClawHub and — on success — auto-resubmits the
     * last query (or transitions to [AIRecommendationUiState.Idle] if none was made yet).
     *
     * No-op unless current state is [AIRecommendationUiState.SkillUnavailable] with
     * [AIRecommendationUiState.SkillUnavailable.canInstall] true and no install already
     * in flight. Without [recommendSkill] (today: no such skill exists) the call does nothing.
     *
     * On terminal failure, stores the error on the state; call [dismissInstallError] to clear.
     * If the error is [SkillInstallError.Unauthorized] the `missingScope` is populated and
     * `canInstall` flipped false so the Install button disappears.
     */
    fun installSkill() {
        val skill = recommendSkill
        val idle = (_state.value as? AIRecommendationUiState.SkillUnavailable)
            ?.takeIf { it.canInstall && it.installProgress == null }
        if (skill == null || idle == null) return

        viewModelScope.launch {
            skill.installer.install(skill.slug).collect { progress ->
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
                _state.value = AIRecommendationUiState.SkillUnavailable(canInstall = recommendSkill != null)
            }
        } else if (_state.value is AIRecommendationUiState.SkillUnavailable) {
            _state.value = AIRecommendationUiState.Idle
        }
    }

    private fun launchApply(changes: List<SuggestedChange>) {
        // Flip isApplying before launching so a second tap in the same frame is already rejected.
        _state.update {
            (it as? AIRecommendationUiState.Ready)
                ?.copy(isApplying = true, applyError = null, confirmSecurityChanges = emptyList()) ?: it
        }
        viewModelScope.launch {
            val outcome = applyChanges(configRepository, changes)
            _state.update {
                (it as? AIRecommendationUiState.Ready)
                    ?.copy(isApplying = false, applySuccess = outcome.allWritten, applyError = outcome.message) ?: it
            }
        }
    }
}

/**
 * Result of [applyChanges].
 *
 * @param allWritten `true` when every section patch was accepted by the gateway.
 * @param message Error text when a write failed or the changes could not be turned into patches, or a
 *   warning when every write landed but reloading the config afterwards failed; `null` otherwise.
 */
private class ApplyOutcome(val allWritten: Boolean, val message: String?)

/**
 * Writes [changes] as one merge-patch per section and reloads the config after every write
 * (ConfigRepository contract). A failed reload does not undo a landed write, and each write already
 * refreshed the ETag, so the remaining sections are still sent and the reload failure is surfaced as
 * a warning on an otherwise successful apply. A failed write stops the apply. [CancellationException]
 * propagates.
 */
private suspend fun applyChanges(configRepository: ConfigRepository, changes: List<SuggestedChange>): ApplyOutcome {
    val patches = try {
        sectionPatches(changes)
    } catch (e: IllegalArgumentException) {
        return ApplyOutcome(allWritten = false, message = "Cannot apply suggestions: ${e.toUserMessage()}")
    }
    var written = 0
    var reloadError: GatewayException? = null
    return try {
        for ((section, patch) in patches) {
            configRepository.updateConfig(section, patch)
            written++
            reloadError = configRepository.reloadFailure() ?: reloadError
        }
        ApplyOutcome(
            allWritten = true,
            message = reloadError?.let {
                "All $written sections applied; reloading config failed: ${it.toUserMessage()}"
            },
        )
    } catch (e: GatewayException) {
        val message = if (written > 0) {
            "Applied $written of ${patches.size} sections; stopped at section ${written + 1}: ${e.toUserMessage()}"
        } else {
            e.toUserMessage()
        }
        ApplyOutcome(allWritten = false, message = message)
    }
}

/** Reloads the config after a write; returns the failure instead of throwing it. */
private suspend fun ConfigRepository.reloadFailure(): GatewayException? = try {
    getConfig()
    null
} catch (e: GatewayException) {
    e
}

/**
 * Assembles context for an AI query. Config/model failures are non-fatal — the request proceeds
 * with an empty config or no model. Uses [suspendRunCatching], so cancellation is always rethrown.
 */
private suspend fun buildRecommendationContext(
    connectionManager: GatewayConnectionManager,
    configRepository: ConfigRepository,
    modelRepository: ModelRepository,
): RecommendationContext {
    val connState = connectionManager.connectionState.value as? GatewayConnection.Connected
    val activeConfig = suspendRunCatching { configRepository.getConfig() }.getOrElse { OpenClawConfig(emptyMap()) }
    val activeModelId = suspendRunCatching { modelRepository.getModels().firstOrNull { it.isActive }?.id }.getOrNull()
    return RecommendationContext(
        activeConfig = activeConfig,
        hardwareInfo = connState?.hardwareInfo,
        activeModelId = activeModelId,
    )
}
