package com.openclaw.ghostcrab.ui

import com.openclaw.ghostcrab.domain.model.OnboardingStep
import com.openclaw.ghostcrab.domain.repository.OnboardingRepository
import com.openclaw.ghostcrab.domain.util.generateToken
import com.openclaw.ghostcrab.ui.onboarding.OnboardingViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException

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

    @Test
    fun `next() from FindOnNetwork marks onboarding completed before emitting Completed`() = runTest {
        val vm = makeVm(savedStep = OnboardingStep.FindOnNetwork)
        fakeRepo.stepProvider = { vm.step.value }
        vm.next()
        assertTrue(fakeRepo.completedCalled)
        assertEquals(OnboardingStep.FindOnNetwork, fakeRepo.stepSeenByMarkCompleted)
        assertEquals(OnboardingStep.Completed, vm.step.value)
    }

    // ── Skip / complete ───────────────────────────────────────────────────────

    @Test
    fun `skip() persists completion before emitting Completed`() = runTest {
        val vm = makeVm()
        fakeRepo.stepProvider = { vm.step.value }
        vm.skip()
        assertEquals(OnboardingStep.Welcome, fakeRepo.stepSeenByMarkCompleted)
        assertEquals(OnboardingStep.Completed, vm.step.value)
    }

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

    @Test
    fun `a failed completion write keeps the step, reports the error and allows a retry`() = runTest {
        val vm = makeVm()
        fakeRepo.markCompletedFailure = IOException("disk full")

        vm.skip()

        assertEquals(OnboardingStep.Welcome, vm.step.value)
        val error = vm.error.value
        assertTrue(error != null && "disk full" in error, "error was: $error")

        vm.clearError()
        fakeRepo.markCompletedFailure = null
        vm.skip()

        assertEquals(OnboardingStep.Completed, vm.step.value)
        assertNull(vm.error.value)
        assertEquals(2, fakeRepo.markCompletedCalls)
    }

    @Test
    fun `taps during the completion write are ignored`() = runTest {
        val vm = makeVm(savedStep = OnboardingStep.InstallGateway)
        val gate = CompletableDeferred<Unit>()
        fakeRepo.markCompletedGate = gate

        vm.skip()
        vm.skip()
        vm.next()
        vm.back()

        assertEquals(OnboardingStep.InstallGateway, vm.step.value)
        assertEquals(1, fakeRepo.markCompletedCalls)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(OnboardingStep.Completed, vm.step.value)
        assertEquals(1, fakeRepo.markCompletedCalls)
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

    @Test
    fun `suggestedToken is a 43-char base64url token and regenerate replaces it`() = runTest {
        val vm = makeVm()
        val first = vm.suggestedToken.value
        assertEquals(43, first.length)
        assertTrue(first.all { it.isLetterOrDigit() || it == '-' || it == '_' })

        vm.regenerateToken()

        val second = vm.suggestedToken.value
        assertEquals(43, second.length)
        assertTrue(first != second)
    }
}

// ── Fake ─────────────────────────────────────────────────────────────────────

private class FakeOnboardingRepository(
    private var savedStep: OnboardingStep = OnboardingStep.Welcome,
    private var completed: Boolean = false,
) : OnboardingRepository {

    var completedCalled = false

    /** Read inside [markCompleted] to record which step the ViewModel showed while persisting. */
    var stepProvider: (() -> OnboardingStep)? = null
    var stepSeenByMarkCompleted: OnboardingStep? = null

    /** Thrown by [markCompleted] while set. */
    var markCompletedFailure: Exception? = null

    /** Awaited by [markCompleted] while set, to hold the write in flight. */
    var markCompletedGate: CompletableDeferred<Unit>? = null
    var markCompletedCalls = 0

    override suspend fun isCompleted(): Boolean = completed

    override suspend fun saveStep(step: OnboardingStep) {
        savedStep = step
    }

    override suspend fun getSavedStep(): OnboardingStep = savedStep

    override suspend fun markCompleted() {
        markCompletedCalls++
        stepSeenByMarkCompleted = stepProvider?.invoke()
        markCompletedGate?.await()
        markCompletedFailure?.let { throw it }
        completed = true
        completedCalled = true
    }

    override suspend fun reset() {
        savedStep = OnboardingStep.Welcome
        completed = false
        completedCalled = false
    }
}
