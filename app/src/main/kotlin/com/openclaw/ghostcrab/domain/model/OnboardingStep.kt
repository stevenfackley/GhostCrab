package com.openclaw.ghostcrab.domain.model

/**
 * Represents each step in the first-launch onboarding walkthrough, in walkthrough order.
 *
 * The declaration order is the step order: [index], [next], [previous] and
 * [ONBOARDING_STEPS_COUNT] all derive from it, so a new step is one entry here plus its screen.
 * The entry names are the strings persisted by the onboarding store, so renaming an entry
 * strands any user who is part-way through on the old name (they restart at [Welcome]).
 *
 * [Completed] is a terminal state that indicates onboarding is done;
 * it is excluded from the visible progress indicator count.
 */
public enum class OnboardingStep {
    Welcome,
    WhatIsOpenClaw,
    InstallGateway,
    StartGateway,
    VerifyRunning,
    FindOnNetwork,
    Completed,
    ;

    /** The step after this one; [Completed] is its own successor. */
    public val next: OnboardingStep
        get() = entries[(ordinal + 1).coerceAtMost(entries.lastIndex)]

    /** The step before this one; [Welcome] is its own predecessor. */
    public val previous: OnboardingStep
        get() = entries[(ordinal - 1).coerceAtLeast(0)]
}

/** Zero-based index of the step. [OnboardingStep.Completed] maps to 6. */
public val OnboardingStep.index: Int
    get() = ordinal

/** Total number of visible onboarding steps, excluding [OnboardingStep.Completed]. */
public val ONBOARDING_STEPS_COUNT: Int = OnboardingStep.entries.size - 1
