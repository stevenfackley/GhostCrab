package com.openclaw.ghostcrab.domain

import com.openclaw.ghostcrab.domain.model.ONBOARDING_STEPS_COUNT
import com.openclaw.ghostcrab.domain.model.OnboardingStep
import com.openclaw.ghostcrab.domain.model.index
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OnboardingStepTest {

    private val inOrder = listOf(
        OnboardingStep.Welcome,
        OnboardingStep.WhatIsOpenClaw,
        OnboardingStep.InstallGateway,
        OnboardingStep.StartGateway,
        OnboardingStep.VerifyRunning,
        OnboardingStep.FindOnNetwork,
        OnboardingStep.Completed,
    )

    @Test
    fun `declaration order is the walkthrough order`() {
        assertEquals(inOrder, OnboardingStep.entries.toList())
    }

    @Test
    fun `index is the step's position in the walkthrough, Completed last`() {
        assertEquals(List(inOrder.size) { it }, inOrder.map { it.index })
    }

    @Test
    fun `next is the following step and Completed is its own successor`() {
        assertEquals(inOrder.drop(1) + OnboardingStep.Completed, inOrder.map { it.next })
    }

    @Test
    fun `previous is the preceding step and Welcome is its own predecessor`() {
        assertEquals(listOf(OnboardingStep.Welcome) + inOrder.dropLast(1), inOrder.map { it.previous })
    }

    @Test
    fun `the progress indicator counts every step except Completed`() {
        assertEquals(6, ONBOARDING_STEPS_COUNT)
        assertEquals(OnboardingStep.Completed.index, ONBOARDING_STEPS_COUNT)
    }
}
