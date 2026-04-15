package com.openclaw.ghostcrab.domain.model

/**
 * Represents each step in the first-launch onboarding walkthrough.
 *
 * [Completed] is a terminal state that indicates onboarding is done;
 * it is excluded from the visible progress indicator count.
 */
public sealed interface OnboardingStep {
    public data object Welcome : OnboardingStep
    public data object WhatIsOpenClaw : OnboardingStep
    public data object InstallGateway : OnboardingStep
    public data object StartGateway : OnboardingStep
    public data object VerifyRunning : OnboardingStep
    public data object FindOnNetwork : OnboardingStep
    public data object Completed : OnboardingStep
}

/** Zero-based index of the step. [OnboardingStep.Completed] maps to 6. */
public val OnboardingStep.index: Int
    get() = when (this) {
        OnboardingStep.Welcome -> 0
        OnboardingStep.WhatIsOpenClaw -> 1
        OnboardingStep.InstallGateway -> 2
        OnboardingStep.StartGateway -> 3
        OnboardingStep.VerifyRunning -> 4
        OnboardingStep.FindOnNetwork -> 5
        OnboardingStep.Completed -> 6
    }

/** Total number of visible onboarding steps, excluding [OnboardingStep.Completed]. */
public const val ONBOARDING_STEPS_COUNT: Int = 6
