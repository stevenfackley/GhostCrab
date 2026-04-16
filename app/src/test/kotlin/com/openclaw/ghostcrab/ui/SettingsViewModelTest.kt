package com.openclaw.ghostcrab.ui

import com.openclaw.ghostcrab.domain.model.ConnectionProfile
import com.openclaw.ghostcrab.domain.model.OnboardingStep
import com.openclaw.ghostcrab.domain.repository.ConnectionProfileRepository
import com.openclaw.ghostcrab.domain.repository.OnboardingRepository
import com.openclaw.ghostcrab.domain.repository.SettingsRepository
import com.openclaw.ghostcrab.ui.settings.SettingsUiState
import com.openclaw.ghostcrab.ui.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [SettingsViewModel].
 *
 * Uses [UnconfinedTestDispatcher] — coroutines run eagerly for synchronous state observation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeEach fun setUp() { Dispatchers.setMain(testDispatcher) }
    @AfterEach fun tearDown() { Dispatchers.resetMain() }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun profile(id: String, name: String = id) = ConnectionProfile(
        id = id,
        displayName = name,
        url = "http://192.168.1.$id:18789",
        lastConnectedAt = null,
        hasToken = false,
    )

    private fun makeVm(
        profiles: List<ConnectionProfile> = emptyList(),
        allowCleartext: Boolean = false,
        profileRepo: FakeSettingsProfileRepository = FakeSettingsProfileRepository(profiles),
        settingsRepo: FakeSettingsRepository = FakeSettingsRepository(allowCleartext),
        onboardingRepo: FakeSettingsOnboardingRepository = FakeSettingsOnboardingRepository(),
    ): SettingsViewModel = SettingsViewModel(profileRepo, settingsRepo, onboardingRepo)

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `initial state is Ready with profiles and cleartext pref`() = runTest {
        val p = profile("1", "Home")
        val vm = makeVm(profiles = listOf(p), allowCleartext = true)
        val state = assertInstanceOf(SettingsUiState.Ready::class.java, vm.state.value)
        assertEquals(1, state.profiles.size)
        assertEquals("Home", state.profiles.first().displayName)
        assertTrue(state.allowCleartextPublicIPs)
    }

    @Test
    fun `setAllowCleartextPublicIPs toggles preference`() = runTest {
        val settingsRepo = FakeSettingsRepository(false)
        val vm = makeVm(settingsRepo = settingsRepo)

        vm.setAllowCleartextPublicIPs(true)

        assertTrue(settingsRepo.currentValue)
    }

    @Test
    fun `requestDeleteProfile sets pendingDeleteProfileId`() = runTest {
        val p = profile("42")
        val vm = makeVm(profiles = listOf(p))

        vm.requestDeleteProfile("42")

        val state = vm.state.value as SettingsUiState.Ready
        assertEquals("42", state.pendingDeleteProfileId)
    }

    @Test
    fun `cancelDeleteProfile clears pendingDeleteProfileId`() = runTest {
        val vm = makeVm(profiles = listOf(profile("1")))

        vm.requestDeleteProfile("1")
        vm.cancelDeleteProfile()

        val state = vm.state.value as SettingsUiState.Ready
        assertNull(state.pendingDeleteProfileId)
    }

    @Test
    fun `confirmDeleteProfile removes profile`() = runTest {
        val profileRepo = FakeSettingsProfileRepository(listOf(profile("1"), profile("2")))
        val vm = makeVm(profileRepo = profileRepo)

        vm.requestDeleteProfile("1")
        vm.confirmDeleteProfile()

        assertEquals(1, profileRepo.deletedIds.size)
        assertEquals("1", profileRepo.deletedIds.first())
    }

    @Test
    fun `confirmClearAllProfiles deletes all profiles`() = runTest {
        val profiles = listOf(profile("a"), profile("b"), profile("c"))
        val profileRepo = FakeSettingsProfileRepository(profiles)
        val vm = makeVm(profileRepo = profileRepo)

        vm.requestClearAllProfiles()
        vm.confirmClearAllProfiles()

        assertEquals(3, profileRepo.deletedIds.size)
    }

    @Test
    fun `cancelClearAllProfiles clears flag`() = runTest {
        val vm = makeVm()

        vm.requestClearAllProfiles()
        vm.cancelClearAllProfiles()

        val state = vm.state.value as SettingsUiState.Ready
        assertFalse(state.showClearAllConfirmation)
    }

    @Test
    fun `replayWalkthrough resets onboarding and sets success flag`() = runTest {
        val onboardingRepo = FakeSettingsOnboardingRepository()
        val vm = makeVm(onboardingRepo = onboardingRepo)

        vm.replayWalkthrough()

        assertTrue(onboardingRepo.wasReset)
        val state = vm.state.value as SettingsUiState.Ready
        assertTrue(state.onboardingResetSuccess)
    }

    @Test
    fun `clearOnboardingResetSuccess clears flag`() = runTest {
        val vm = makeVm()

        vm.replayWalkthrough()
        vm.clearOnboardingResetSuccess()

        val state = vm.state.value as SettingsUiState.Ready
        assertFalse(state.onboardingResetSuccess)
    }

    @Test
    fun `startEditProfile sets editingProfile`() = runTest {
        val p = profile("77", "Office")
        val vm = makeVm(profiles = listOf(p))

        vm.startEditProfile(p)

        val state = vm.state.value as SettingsUiState.Ready
        assertNotNull(state.editingProfile)
        assertEquals("77", state.editingProfile?.id)
    }

    @Test
    fun `cancelEditProfile clears editingProfile`() = runTest {
        val p = profile("77", "Office")
        val vm = makeVm(profiles = listOf(p))

        vm.startEditProfile(p)
        vm.cancelEditProfile()

        val state = vm.state.value as SettingsUiState.Ready
        assertNull(state.editingProfile)
    }

    @Test
    fun `saveProfileEdit persists updated display name`() = runTest {
        val p = profile("5", "Old Name")
        val profileRepo = FakeSettingsProfileRepository(listOf(p))
        val vm = makeVm(profileRepo = profileRepo)

        vm.startEditProfile(p)
        vm.saveProfileEdit("New Name", null)

        val saved = profileRepo.savedProfiles.lastOrNull()
        assertNotNull(saved)
        assertEquals("New Name", saved?.first?.displayName)
    }
}

// ── Fakes ─────────────────────────────────────────────────────────────────────

private class FakeSettingsProfileRepository(
    initial: List<ConnectionProfile>,
) : ConnectionProfileRepository {

    private val flow = MutableStateFlow(initial)
    val deletedIds = mutableListOf<String>()
    val savedProfiles = mutableListOf<Pair<ConnectionProfile, String?>>()

    override fun getProfiles(): Flow<List<ConnectionProfile>> = flow

    override suspend fun saveProfile(profile: ConnectionProfile, token: String?) {
        savedProfiles += profile to token
        flow.value = flow.value.map { if (it.id == profile.id) profile else it }
    }

    override suspend fun getToken(profileId: String): String? = null

    override suspend fun deleteProfile(profileId: String) {
        deletedIds += profileId
        flow.value = flow.value.filter { it.id != profileId }
    }
}

private class FakeSettingsRepository(initial: Boolean) : SettingsRepository {

    private val flow = MutableStateFlow(initial)
    var currentValue: Boolean = initial
        private set

    override val allowCleartextPublicIPs: Flow<Boolean> = flow

    override suspend fun setAllowCleartextPublicIPs(enabled: Boolean) {
        currentValue = enabled
        flow.value = enabled
    }
}

private class FakeSettingsOnboardingRepository : OnboardingRepository {

    var wasReset = false

    override suspend fun isCompleted(): Boolean = true
    override suspend fun saveStep(step: OnboardingStep) = Unit
    override suspend fun getSavedStep(): OnboardingStep = OnboardingStep.Welcome
    override suspend fun markCompleted() = Unit
    override suspend fun reset() { wasReset = true }
}
