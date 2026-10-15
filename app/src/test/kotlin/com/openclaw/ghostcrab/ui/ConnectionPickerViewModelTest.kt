package com.openclaw.ghostcrab.ui

import app.cash.turbine.test
import com.openclaw.ghostcrab.domain.exception.GatewayPairingRequiredException
import com.openclaw.ghostcrab.domain.exception.GatewayUnreachableException
import com.openclaw.ghostcrab.domain.model.AuthRequirement
import com.openclaw.ghostcrab.domain.model.ConnectionProfile
import com.openclaw.ghostcrab.domain.model.ConnectionWarning
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import com.openclaw.ghostcrab.domain.model.OnboardingStep
import com.openclaw.ghostcrab.domain.repository.ConnectionProfileRepository
import com.openclaw.ghostcrab.domain.repository.GatewayConnectionManager
import com.openclaw.ghostcrab.domain.repository.OnboardingRepository
import com.openclaw.ghostcrab.ui.connection.ConnectionPickerEvent
import com.openclaw.ghostcrab.ui.connection.ConnectionPickerUiState
import com.openclaw.ghostcrab.ui.connection.ConnectionPickerViewModel
import com.openclaw.ghostcrab.ui.connection.PAIRING_RETRY_INTERVAL_MS
import com.openclaw.ghostcrab.ui.connection.PAIRING_TIMEOUT_MS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionPickerViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeEach
    fun setUp() { Dispatchers.setMain(testDispatcher) }

    @AfterEach
    fun tearDown() { Dispatchers.resetMain() }

    private fun profile(id: String = "id1", hasToken: Boolean = false) = ConnectionProfile(
        id = id,
        displayName = "Test Gateway",
        url = "http://192.168.1.50:18789",
        lastConnectedAt = null,
        hasToken = hasToken,
    )

    private fun makeVm(
        profiles: List<ConnectionProfile> = emptyList(),
        onboardingCompleted: Boolean = false,
    ): Triple<ConnectionPickerViewModel, FakeCpProfileRepo, FakeCpConnectionManager> {
        val repo = FakeCpProfileRepo(profiles)
        val manager = FakeCpConnectionManager()
        val onboarding = FakeCpOnboardingRepo(onboardingCompleted)
        return Triple(ConnectionPickerViewModel(repo, manager, onboarding), repo, manager)
    }

    @Test
    fun `no profiles and onboarding incomplete triggers showOnboarding`() = runTest {
        val (vm) = makeVm(profiles = emptyList(), onboardingCompleted = false)
        assertTrue(vm.showOnboarding.value)
    }

    @Test
    fun `no profiles but onboarding complete does not trigger showOnboarding`() = runTest {
        val (vm) = makeVm(profiles = emptyList(), onboardingCompleted = true)
        assertFalse(vm.showOnboarding.value)
    }

    @Test
    fun `existing profiles suppresses showOnboarding even when onboarding incomplete`() = runTest {
        val (vm) = makeVm(profiles = listOf(profile()), onboardingCompleted = false)
        assertFalse(vm.showOnboarding.value)
    }

    @Test
    fun `onOnboardingNavigated resets showOnboarding to false`() = runTest {
        val (vm) = makeVm(profiles = emptyList(), onboardingCompleted = false)
        assertTrue(vm.showOnboarding.value)
        vm.onOnboardingNavigated()
        assertFalse(vm.showOnboarding.value)
    }

    @Test
    fun `delete delegates to profileRepository`() = runTest {
        val (vm, repo) = makeVm()
        vm.delete("id-to-delete")
        assertEquals(listOf("id-to-delete"), repo.deletedIds)
    }

    @Test
    fun `connect without token calls connectionManager with null token and navigates`() = runTest {
        val p = profile(hasToken = false)
        val (vm, _, manager) = makeVm(profiles = listOf(p))

        vm.events.test {
            vm.connect(p)
            assertEquals(ConnectionPickerEvent.NavigateToDashboard, awaitItem())
        }
        assertEquals(listOf(Pair(p.url, null as String?)), manager.connectCalls)
        assertNull(vm.uiState.value.connectingProfileId)
    }

    @Test
    fun `connect with token retrieves token then passes it to connectionManager`() = runTest {
        val p = profile(id = "tok-id", hasToken = true)
        val (vm, repo, manager) = makeVm(profiles = listOf(p))
        repo.tokens["tok-id"] = "secret-token"

        vm.connect(p)

        assertEquals(listOf(Pair(p.url, "secret-token")), manager.connectCalls)
    }

    @Test
    fun `connect failure surfaces errorMessage and clears connecting state`() = runTest {
        val p = profile()
        val manager = object : FakeCpConnectionManager() {
            override suspend fun connect(url: String, token: String?) {
                throw GatewayUnreachableException(url)
            }
        }
        val vm = ConnectionPickerViewModel(FakeCpProfileRepo(listOf(p)), manager, FakeCpOnboardingRepo(true))

        vm.connect(p)

        val state = vm.uiState.value
        assertNull(state.connectingProfileId)
        assertTrue(state.errorMessage.orEmpty().contains("GatewayUnreachableException"))
        vm.clearError()
        assertNull(vm.uiState.value.errorMessage)
    }

    // ── Pairing approval (ADR-0007) ───────────────────────────────────────────

    private fun pendingApproval() =
        GatewayPairingRequiredException("ws://gw/ws", "req-9", "not-paired", emptyList())

    @Test
    fun `pairing required shows the prompt on the list and connects once approved`() = runTest {
        val p = profile()
        var calls = 0
        val manager = object : FakeCpConnectionManager() {
            override suspend fun connect(url: String, token: String?) {
                if (calls++ == 0) {
                    throw GatewayPairingRequiredException("ws://gw/ws", "req-9", "not-paired", emptyList())
                }
            }
        }
        val vm = ConnectionPickerViewModel(FakeCpProfileRepo(listOf(p)), manager, FakeCpOnboardingRepo(true))
        vm.events.test {
            vm.connect(p)
            assertEquals("req-9", vm.uiState.value.pairingPrompt?.requestId)
            advanceTimeBy(PAIRING_RETRY_INTERVAL_MS + 1)
            assertEquals(ConnectionPickerEvent.NavigateToDashboard, awaitItem())
        }
        assertEquals(ConnectionPickerUiState(), vm.uiState.value)
    }

    @Test
    fun `cancelPairing clears the prompt and the connecting state`() = runTest {
        val p = profile()
        val manager = object : FakeCpConnectionManager() {
            override suspend fun connect(url: String, token: String?) {
                throw GatewayPairingRequiredException("ws://gw/ws", "req-9", "not-paired", emptyList())
            }
        }
        val vm = ConnectionPickerViewModel(FakeCpProfileRepo(listOf(p)), manager, FakeCpOnboardingRepo(true))
        vm.connect(p)
        vm.pairing.cancel()
        assertEquals(ConnectionPickerUiState(), vm.uiState.value)
    }

    @Test
    fun `checkPairingNow retries without waiting`() = runTest {
        val p = profile()
        var calls = 0
        val manager = object : FakeCpConnectionManager() {
            override suspend fun connect(url: String, token: String?) {
                if (calls++ == 0) throw pendingApproval()
            }
        }
        val vm = ConnectionPickerViewModel(FakeCpProfileRepo(listOf(p)), manager, FakeCpOnboardingRepo(true))
        vm.connect(p)
        vm.pairing.checkNow()
        runCurrent()
        assertEquals(2, calls)
        assertEquals(ConnectionPickerUiState(), vm.uiState.value)
    }

    @Test
    fun `the prompt explains a regenerated device identity`() = runTest {
        val p = profile()
        val manager = object : FakeCpConnectionManager() {
            override val connectionState: StateFlow<GatewayConnection> = MutableStateFlow(
                GatewayConnection.Error(p.url, pendingApproval(), listOf(ConnectionWarning.DeviceIdentityRegenerated)),
            )
            override suspend fun connect(url: String, token: String?) {
                throw pendingApproval()
            }
        }
        val vm = ConnectionPickerViewModel(FakeCpProfileRepo(listOf(p)), manager, FakeCpOnboardingRepo(true))
        vm.connect(p)
        assertEquals(listOf(ConnectionWarning.DeviceIdentityRegenerated), vm.uiState.value.pairingPrompt?.warnings)
        vm.pairing.cancel()
    }

    @Test
    fun `an expired approval window is an explicit error`() = runTest {
        val p = profile()
        val manager = object : FakeCpConnectionManager() {
            override suspend fun connect(url: String, token: String?) {
                throw pendingApproval()
            }
        }
        val vm = ConnectionPickerViewModel(FakeCpProfileRepo(listOf(p)), manager, FakeCpOnboardingRepo(true))
        vm.connect(p)
        advanceTimeBy(PAIRING_TIMEOUT_MS + 1)
        val state = vm.uiState.value
        assertNull(state.pairingPrompt)
        assertNull(state.connectingProfileId)
        assertTrue(state.errorMessage.orEmpty().contains("PAIRING_REQUIRED"))
    }
}

// ── Fakes ─────────────────────────────────────────────────────────────────────

private class FakeCpProfileRepo(
    profiles: List<ConnectionProfile> = emptyList(),
) : ConnectionProfileRepository {
    private val _profiles = MutableStateFlow(profiles)
    val deletedIds = mutableListOf<String>()
    val tokens = mutableMapOf<String, String>()

    override fun getProfiles() = _profiles
    override suspend fun saveProfile(profile: ConnectionProfile, token: String?) {
        _profiles.value = _profiles.value + profile
    }
    override suspend fun deleteProfile(profileId: String) {
        deletedIds += profileId
        _profiles.value = _profiles.value.filter { it.id != profileId }
    }
    override suspend fun getToken(profileId: String): String? = tokens[profileId]
    override suspend fun clearAll() {
        _profiles.value = emptyList()
    }
}

private open class FakeCpConnectionManager : GatewayConnectionManager {
    val connectCalls = mutableListOf<Pair<String, String?>>()
    override val connectionState: StateFlow<GatewayConnection> =
        MutableStateFlow(GatewayConnection.Disconnected)
    override suspend fun probeAuth(url: String) = AuthRequirement.None
    override suspend fun connect(url: String, token: String?) { connectCalls += Pair(url, token) }
    override suspend fun disconnect() {}
}

private class FakeCpOnboardingRepo(private val completed: Boolean) : OnboardingRepository {
    override suspend fun isCompleted() = completed
    override suspend fun saveStep(step: OnboardingStep) {}
    override suspend fun getSavedStep() = OnboardingStep.Welcome
    override suspend fun markCompleted() {}
    override suspend fun reset() {}
}
