package com.openclaw.ghostcrab.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openclaw.ghostcrab.domain.model.OnboardingStep
import com.openclaw.ghostcrab.domain.repository.OnboardingRepository
import com.openclaw.ghostcrab.domain.util.generateToken
import com.openclaw.ghostcrab.domain.util.suspendRunCatching
import com.openclaw.ghostcrab.ui.toUserMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the onboarding walkthrough.
 *
 * Persists progress via [OnboardingRepository] so the user can resume after backgrounding.
 * Navigation between steps is linear; [OnboardingStep.Completed] is the terminal state.
 *
 * @param repository Stores and retrieves onboarding progress.
 */
public class OnboardingViewModel(
    private val repository: OnboardingRepository,
) : ViewModel() {

    private val _step = MutableStateFlow<OnboardingStep>(OnboardingStep.Welcome)

    /** Current onboarding step. Emits [OnboardingStep.Completed] when done. */
    public val step: StateFlow<OnboardingStep> = _step.asStateFlow()

    private val _suggestedToken = MutableStateFlow(generateToken())

    /**
     * Cryptographically random token suggested on the Start Gateway step. Held here rather
     * than in `rememberSaveable` so it survives rotation without being written to the
     * saved-instance Bundle, which the system persists to disk and exposes via `dumpsys`.
     */
    public val suggestedToken: StateFlow<String> = _suggestedToken.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)

    /** Why completing onboarding failed, formatted for display; cleared by [clearError]. */
    public val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * True while the completion write runs and after it succeeds. [next], [back] and [skip] are
     * ignored meanwhile, so a double tap or a Back during the write cannot start a second write
     * or move the pager under it. Reset when the write fails, so the user can retry.
     */
    private var completing = false

    init {
        viewModelScope.launch {
            val saved = repository.getSavedStep()
            if (saved != OnboardingStep.Completed) _step.value = saved
        }
    }

    /** Advances to the next step and persists the change. Ignored while completing. */
    public fun next() {
        if (completing) return
        viewModelScope.launch { advance(forward = true) }
    }

    /**
     * Returns to the previous step and persists the change. No-op at [OnboardingStep.Welcome];
     * ignored while completing.
     */
    public fun back() {
        if (completing) return
        viewModelScope.launch { advance(forward = false) }
    }

    /** Replaces [suggestedToken] with a fresh random value. */
    public fun regenerateToken() {
        _suggestedToken.value = generateToken()
    }

    /** Skips directly to completion, marking onboarding done. Ignored while completing. */
    public fun skip() {
        if (completing) return
        viewModelScope.launch { complete() }
    }

    /** Clears [error] once it has been shown. */
    public fun clearError() {
        _error.value = null
    }

    private suspend fun advance(forward: Boolean) {
        if (completing) return
        val current = _step.value
        val next = when {
            forward -> when (current) {
                OnboardingStep.Welcome -> OnboardingStep.WhatIsOpenClaw
                OnboardingStep.WhatIsOpenClaw -> OnboardingStep.InstallGateway
                OnboardingStep.InstallGateway -> OnboardingStep.StartGateway
                OnboardingStep.StartGateway -> OnboardingStep.VerifyRunning
                OnboardingStep.VerifyRunning -> OnboardingStep.FindOnNetwork
                OnboardingStep.FindOnNetwork -> OnboardingStep.Completed
                OnboardingStep.Completed -> OnboardingStep.Completed
            }
            else -> when (current) {
                OnboardingStep.Welcome -> OnboardingStep.Welcome
                OnboardingStep.WhatIsOpenClaw -> OnboardingStep.Welcome
                OnboardingStep.InstallGateway -> OnboardingStep.WhatIsOpenClaw
                OnboardingStep.StartGateway -> OnboardingStep.InstallGateway
                OnboardingStep.VerifyRunning -> OnboardingStep.StartGateway
                OnboardingStep.FindOnNetwork -> OnboardingStep.VerifyRunning
                OnboardingStep.Completed -> OnboardingStep.FindOnNetwork
            }
        }
        if (next == OnboardingStep.Completed) {
            complete()
            return
        }
        _step.value = next
        repository.saveStep(next)
    }

    /**
     * Persists completion before emitting [OnboardingStep.Completed]: the screen navigates to the
     * picker on that emission, and the picker's first-run check reads the stored flag. Emitting
     * first raced the write and bounced the user back into onboarding.
     *
     * A failed write (DataStore I/O) leaves the current step in place and reports through [error]
     * instead of crashing `viewModelScope`.
     */
    private suspend fun complete() {
        if (completing) return
        completing = true
        suspendRunCatching { repository.markCompleted() }
            .onSuccess { _step.value = OnboardingStep.Completed }
            .onFailure { e ->
                completing = false
                _error.value = "Could not save onboarding progress: ${e.toUserMessage()}"
            }
    }
}
