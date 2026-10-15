package com.openclaw.ghostcrab.ui

import com.openclaw.ghostcrab.data.gateway.SCOPE_OPERATOR_ADMIN
import com.openclaw.ghostcrab.data.gateway.SCOPE_OPERATOR_READ
import com.openclaw.ghostcrab.data.gateway.gatewayCapabilities
import com.openclaw.ghostcrab.domain.exception.DeviceCredentialStorageException
import com.openclaw.ghostcrab.domain.model.AuthRequirement
import com.openclaw.ghostcrab.domain.model.ConnectionWarning
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import com.openclaw.ghostcrab.domain.model.ModelInfo
import com.openclaw.ghostcrab.domain.repository.GatewayConnectionManager
import com.openclaw.ghostcrab.domain.repository.GatewayHealthChecker
import com.openclaw.ghostcrab.domain.repository.ModelRepository
import com.openclaw.ghostcrab.ui.dashboard.DashboardUiState
import com.openclaw.ghostcrab.ui.dashboard.DashboardViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.GeneralSecurityException

/**
 * Unit tests for [DashboardViewModel].
 *
 * With [UnconfinedTestDispatcher] all state transitions driven by [MutableStateFlow] emissions
 * happen synchronously (eagerly) — no [advanceUntilIdle] needed unless we want to advance past a
 * [kotlinx.coroutines.delay]. The infinite health poll loop means [advanceUntilIdle] must NOT be
 * called for tests that leave polling active, or the test will hang.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private val connectedState = GatewayConnection.Connected(
        url = "http://192.168.1.50:18789",
        displayName = "Home Gateway",
        version = "1.0.0",
        authRequirement = AuthRequirement.Token,
        isHttps = false,
        capabilities = listOf("skill-ai-recommend"),
    )

    private fun model(id: String, active: Boolean = false) = ModelInfo(
        id = id,
        provider = "openai",
        displayName = id,
        isActive = active,
        status = "ready",
        capabilities = emptyList(),
    )

    private fun makeVm(
        initialConnection: GatewayConnection = GatewayConnection.Disconnected,
        models: List<ModelInfo> = emptyList(),
        healthResult: suspend () -> Unit = {},
    ): Pair<DashboardViewModel, MutableStateFlow<GatewayConnection>> {
        val connFlow = MutableStateFlow(initialConnection)
        val vm = DashboardViewModel(
            connectionManager = FakeDashboardConnectionManager(connFlow),
            modelRepository = FakeModelRepository(models),
            healthChecker = healthChecker { healthResult() },
        )
        return vm to connFlow
    }

    // ── State machine ─────────────────────────────────────────────────────────

    @Test
    fun `initial state with no active connection is Disconnected`() {
        // With UnconfinedTestDispatcher the init coroutine runs eagerly, immediately processing
        // the initial Disconnected connection state — so the observable initial UI state is Disconnected.
        val (vm, _) = makeVm()
        assertInstanceOf(DashboardUiState.Disconnected::class.java, vm.state.value)
    }

    @Test
    fun `Connected state transitions to Ready with models`() = runTest {
        val (vm, connFlow) = makeVm(models = listOf(model("gpt-4o", active = true)))
        connFlow.value = connectedState

        val state = vm.state.value as DashboardUiState.Ready
        assertEquals(connectedState, state.connection)
        assertEquals(1, state.models.size)
        assertTrue(state.models.first().isActive)

        // Cancel the poll loop so runTest does not advance through the infinite delay
        connFlow.value = GatewayConnection.Disconnected
    }

    @Test
    fun `a hello-ok without scopes offers no config, models or skills`() = runTest {
        val (vm, connFlow) = makeVm()
        connFlow.value = connectedState.copy(capabilities = gatewayCapabilities(ALL_METHODS, scopes = emptySet()))

        val ready = vm.state.value as DashboardUiState.Ready
        assertEquals(GatewayFeatures(config = false, models = false, skills = false), ready.features)

        connFlow.value = GatewayConnection.Disconnected
    }

    @Test
    fun `a hello-ok with the admin and read scopes offers config, models and skills`() = runTest {
        val (vm, connFlow) = makeVm()
        val scopes = setOf(SCOPE_OPERATOR_ADMIN, SCOPE_OPERATOR_READ)
        connFlow.value = connectedState.copy(capabilities = gatewayCapabilities(ALL_METHODS, scopes))

        val ready = vm.state.value as DashboardUiState.Ready
        assertEquals(GatewayFeatures(config = true, models = true, skills = true), ready.features)

        connFlow.value = GatewayConnection.Disconnected
    }

    @Test
    fun `a device token that could not be saved is exposed for the dashboard notice`() = runTest {
        val (vm, connFlow) = makeVm()
        val failure = DeviceCredentialStorageException(
            "save the device token for http://192.168.1.50:18789",
            GeneralSecurityException("keystore locked"),
        )
        connFlow.value = connectedState.copy(warnings = listOf(ConnectionWarning.DeviceTokenNotSaved(failure)))

        val ready = vm.state.value as DashboardUiState.Ready
        assertEquals(failure.toUserMessage(), ready.deviceTokenNotSaved)

        connFlow.value = GatewayConnection.Disconnected
    }

    @Test
    fun `a session without warnings shows no device-token notice`() = runTest {
        val (vm, connFlow) = makeVm()
        connFlow.value = connectedState.copy(warnings = listOf(ConnectionWarning.DeviceIdentityRegenerated))

        assertNull((vm.state.value as DashboardUiState.Ready).deviceTokenNotSaved)

        connFlow.value = GatewayConnection.Disconnected
    }

    @Test
    fun `a silent reconnect keeps the dashboard as is and the reconnected session reloads it`() = runTest {
        val models = FakeModelRepository(listOf(model("gpt-4o")))
        val connFlow = MutableStateFlow<GatewayConnection>(GatewayConnection.Disconnected)
        val vm = DashboardViewModel(FakeDashboardConnectionManager(connFlow), models, healthChecker {})
        connFlow.value = connectedState
        val ready = vm.state.value

        connFlow.value = connectedState.copy(reconnecting = true)
        assertSame(ready, vm.state.value)
        assertEquals(1, models.loads)

        val reconnected = connectedState.copy(sessionGeneration = 1)
        connFlow.value = reconnected
        assertEquals(2, models.loads)
        assertEquals(reconnected, (vm.state.value as DashboardUiState.Ready).connection)

        connFlow.value = GatewayConnection.Disconnected
    }

    @Test
    fun `successful health poll updates lastOkMs`() = runTest {
        val (vm, connFlow) = makeVm()
        connFlow.value = connectedState

        val state = vm.state.value as DashboardUiState.Ready
        assertNotNull(state.health.lastOkMs)
        assertNull(state.health.lastError)

        connFlow.value = GatewayConnection.Disconnected
    }

    @Test
    fun `two consecutive health failures transition to Degraded`() = runTest {
        var callCount = 0
        val (vm, connFlow) = makeVm(healthResult = {
            callCount++
            throw RuntimeException("connection refused")
        })
        // Eager: first health check fails immediately (no delay before it)
        connFlow.value = connectedState
        // State is Ready with lastError set; pollJob suspended at delay(30_000)

        // Advance past one poll interval → second health check fails → Degraded + loop breaks
        advanceTimeBy(DashboardViewModel.POLL_INTERVAL_MS + 100)
        // advanceUntilIdle() is safe now because the loop broke on Degraded

        val state = vm.state.value
        assertInstanceOf(DashboardUiState.Degraded::class.java, state)
        assertTrue((state as DashboardUiState.Degraded).reason.contains("connection refused"))
    }

    @Test
    fun `one failure then success resets consecutive count to zero`() = runTest {
        var callCount = 0
        val (vm, connFlow) = makeVm(healthResult = {
            callCount++
            if (callCount == 1) throw RuntimeException("timeout")
        })
        connFlow.value = connectedState
        val afterFirstFail = vm.state.value as DashboardUiState.Ready
        assertNotNull(afterFirstFail.health.lastError)

        advanceTimeBy(DashboardViewModel.POLL_INTERVAL_MS + 100)

        val state = vm.state.value as DashboardUiState.Ready
        assertNull(state.health.lastError)
        assertNotNull(state.health.lastOkMs)

        connFlow.value = GatewayConnection.Disconnected
    }

    @Test
    fun `Disconnected connection state emits Disconnected ui state`() = runTest {
        val (vm, connFlow) = makeVm(initialConnection = connectedState)
        // Eager: initial connected state already processed
        assertInstanceOf(DashboardUiState.Ready::class.java, vm.state.value)

        // Eager: disconnect transitions happen synchronously
        connFlow.value = GatewayConnection.Disconnected

        assertInstanceOf(DashboardUiState.Disconnected::class.java, vm.state.value)
    }

    @Test
    fun `Error connection state emits Disconnected ui state`() = runTest {
        val (vm, connFlow) = makeVm(initialConnection = connectedState)

        connFlow.value = GatewayConnection.Error(
            url = connectedState.url,
            cause = com.openclaw.ghostcrab.domain.exception.GatewayUnreachableException(
                connectedState.url,
                RuntimeException("lost"),
            ),
        )

        assertInstanceOf(DashboardUiState.Disconnected::class.java, vm.state.value)
    }

    @Test
    fun `disconnect() delegates to connectionManager`() = runTest {
        val manager = FakeDashboardConnectionManager(MutableStateFlow(connectedState))
        val vm = DashboardViewModel(
            connectionManager = manager,
            modelRepository = FakeModelRepository(),
            healthChecker = healthChecker {},
        )
        // Eager: disconnect triggers the launch in viewModelScope synchronously
        vm.disconnect()
        assertTrue(manager.disconnectCalled)
    }

    @Test
    fun `Ready state is emitted before first health poll completes`() = runTest {
        // With UnconfinedTestDispatcher: Ready is emitted THEN healthChecker is invoked
        var readySeenBeforeHealth = false
        val connFlow = MutableStateFlow<GatewayConnection>(GatewayConnection.Disconnected)
        lateinit var vm: DashboardViewModel
        vm = DashboardViewModel(
            connectionManager = FakeDashboardConnectionManager(connFlow),
            modelRepository = FakeModelRepository(),
            healthChecker = healthChecker {
                readySeenBeforeHealth = vm.state.value is DashboardUiState.Ready
            },
        )
        connFlow.value = connectedState
        assertTrue(readySeenBeforeHealth)

        connFlow.value = GatewayConnection.Disconnected
    }

    private companion object {
        /** `hello-ok.features.methods` of a gateway that serves every screen. */
        val ALL_METHODS = setOf("config.get", "config.patch", "models.list", "skills.status")
    }
}

// ── Fakes ─────────────────────────────────────────────────────────────────────

private class FakeDashboardConnectionManager(
    private val flow: MutableStateFlow<GatewayConnection>,
) : GatewayConnectionManager {

    var disconnectCalled = false

    override val connectionState: StateFlow<GatewayConnection> = flow

    override suspend fun probeAuth(url: String) = AuthRequirement.None

    override suspend fun connect(url: String, token: String?) {
        flow.value = GatewayConnection.Connecting(url)
    }

    override suspend fun disconnect() {
        disconnectCalled = true
        flow.value = GatewayConnection.Disconnected
    }
}

private class FakeModelRepository(
    private val models: List<ModelInfo> = emptyList(),
) : ModelRepository {
    var loads = 0
        private set

    override suspend fun getModels(): List<ModelInfo> {
        loads++
        return models
    }
    override suspend fun setActiveModel(modelId: String) = Unit
}

private fun healthChecker(block: suspend () -> Unit): GatewayHealthChecker = object : GatewayHealthChecker {
    override suspend fun check() = block()
}
