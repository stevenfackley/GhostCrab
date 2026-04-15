package com.openclaw.ghostcrab.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openclaw.ghostcrab.domain.model.OnboardingStep
import com.openclaw.ghostcrab.domain.repository.OnboardingRepository
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

    init {
        viewModelScope.launch {
            val saved = repository.getSavedStep()
            if (saved != OnboardingStep.Completed) _step.value = saved
        }
    }

    /** Advances to the next step and persists the change. */
    public fun next() {
        viewModelScope.launch { advance(forward = true) }
    }

    /** Returns to the previous step and persists the change. No-op at [OnboardingStep.Welcome]. */
    public fun back() {
        viewModelScope.launch { advance(forward = false) }
    }

    /** Skips directly to completion, marking onboarding done. */
    public fun skip() {
        viewModelScope.launch { complete() }
    }

    /**
     * Called when the user successfully connects to a gateway during onboarding.
     * Marks onboarding as completed.
     */
    public fun markOnboardingConnected() {
        viewModelScope.launch { complete() }
    }

    private suspend fun advance(forward: Boolean) {
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
        _step.value = next
        repository.saveStep(next)
    }

    private suspend fun complete() {
        _step.value = OnboardingStep.Completed
        repository.markCompleted()
    }

    public companion object {
        /**
         * Generates a cryptographically random 32-byte token encoded as URL-safe Base64.
         *
         * @return A 43-character base64url string (no padding) suitable for use as a bearer token.
         */
        public fun generateToken(): String {
            val bytes = ByteArray(32)
            java.security.SecureRandom().nextBytes(bytes)
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
