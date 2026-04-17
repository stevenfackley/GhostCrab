package com.openclaw.ghostcrab.ui

import com.openclaw.ghostcrab.domain.model.OnboardingStep
import com.openclaw.ghostcrab.domain.repository.OnboardingRepository
import com.openclaw.ghostcrab.domain.util.generateToken
import com.openclaw.ghostcrab.ui.onboarding.OnboardingViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [OnboardingViewModel].
 *
 * [UnconfinedTestDispatcher] makes all `viewModelScope.launch` coroutines run eagerly,
 * so assertions can be made immediately after calling public functions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var fakeRepo: FakeOnboardingRepository

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun makeVm(
        savedStep: OnboardingStep = OnboardingStep.Welcome,
        completed: Boolean = false,
    ): OnboardingViewModel {
        fakeRepo = FakeOnboardingRepository(savedStep = savedStep, completed = completed)
        return OnboardingViewModel(fakeRepo)
    }

    // ── Initial state ─────────────────────────────────────────────────────────

    @Test
    fun `initial step is Welcome when repository returns Welcome`() = runTest {
        val vm = makeVm(savedStep = OnboardingStep.Welcome)
        assertEquals(OnboardingStep.Welcome, vm.step.value)
    }

    @Test
    fun `init loads saved step from repository`() = runTest {
        val vm = makeVm(savedStep = OnboardingStep.InstallGateway)
        assertEquals(OnboardingStep.InstallGateway, vm.step.value)
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    @Test
    fun `next() advances to WhatIsOpenClaw from Welcome`() = runTest {
        val vm = makeVm()
        vm.next()
        assertEquals(OnboardingStep.WhatIsOpenClaw, vm.step.value)
    }

    @Test
    fun `back() does nothing at Welcome`() = runTest {
        val vm = makeVm()
        vm.back()
        assertEquals(OnboardingStep.Welcome, vm.step.value)
    }

    @Test
    fun `next() from FindOnNetwork emits Completed`() = runTest {
        val vm = makeVm(savedStep = OnboardingStep.FindOnNetwork)
        vm.next()
        assertEquals(OnboardingStep.Completed, vm.step.value)
    }

    // ── Skip / complete ───────────────────────────────────────────────────────

    @Test
    fun `skip() emits Completed and calls markCompleted`() = runTest {
        val vm = makeVm()
        vm.skip()
        assertEquals(OnboardingStep.Completed, vm.step.value)
        assertTrue(fakeRepo.completedCalled)
    }

    @Test
    fun `skip() from mid-flow step emits Completed and calls markCompleted`() = runTest {
        val vm = makeVm(savedStep = OnboardingStep.InstallGateway)
        vm.skip()
        assertEquals(OnboardingStep.Completed, vm.step.value)
        assertTrue(fakeRepo.completedCalled)
    }

    // ── Token generation ──────────────────────────────────────────────────────

    @Test
    fun `generateToken returns 32-byte base64url string`() {
        val token = generateToken()
        // 32 bytes base64url-encoded without padding = ceil(32 * 4/3) = 43 chars
        assertEquals(43, token.length)
        // Must only contain URL-safe base64 chars (A-Z a-z 0-9 - _)
        assertTrue(token.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }
}

// ── Fake ─────────────────────────────────────────────────────────────────────

private class FakeOnboardingRepository(
    private var savedStep: OnboardingStep = OnboardingStep.Welcome,
    private var completed: Boolean = false,
) : OnboardingRepository {

    var completedCalled = false

    override suspend fun isCompleted(): Boolean = completed

    override suspend fun saveStep(step: OnboardingStep) {
        savedStep = step
    }

    override suspend fun getSavedStep(): OnboardingStep = savedStep

    override suspend fun markCompleted() {
        completed = true
        completedCalled = true
    }

    override suspend fun reset() {
        savedStep = OnboardingStep.Welcome
        completed = false
        completedCalled = false
    }
}
