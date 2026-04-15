package com.openclaw.ghostcrab.domain.repository

import com.openclaw.ghostcrab.domain.model.OnboardingStep

/**
 * Persists onboarding progress across app sessions.
 *
 * All functions are safe to call from any dispatcher.
 */
public interface OnboardingRepository {

    /**
     * Returns `true` if the user has previously completed or skipped onboarding.
     */
    public suspend fun isCompleted(): Boolean

    /**
     * Saves the current [step] so it can be resumed on next launch.
     *
     * @param step The step to persist. Passing [OnboardingStep.Completed] does not
     *   automatically set the completed flag — use [markCompleted] for that.
     */
    public suspend fun saveStep(step: OnboardingStep)

    /**
     * Returns the last-saved step, or [OnboardingStep.Welcome] if none stored.
     */
    public suspend fun getSavedStep(): OnboardingStep

    /**
     * Marks onboarding as fully completed. Subsequent calls to [isCompleted] return `true`.
     */
    public suspend fun markCompleted()

    /**
     * Resets all onboarding state so the walkthrough will be shown again on next launch.
     */
    public suspend fun reset()
}
