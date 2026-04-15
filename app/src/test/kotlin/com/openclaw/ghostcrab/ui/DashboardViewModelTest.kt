package com.openclaw.ghostcrab.ui

import com.openclaw.ghostcrab.data.api.dto.HealthResponse
import com.openclaw.ghostcrab.domain.model.AuthRequirement
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import com.openclaw.ghostcrab.domain.model.ModelInfo
import com.openclaw.ghostcrab.domain.repository.GatewayConnectionManager
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

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
        healthResult: suspend () -> HealthResponse = { HealthResponse("ok") },
    ): Pair<DashboardViewModel, MutableStateFlow<GatewayConnection>> {
        val connFlow = MutableStateFlow(initialConnection)
        val vm = DashboardViewModel(
            connectionManager = FakeDashboardConnectionManager(connFlow),
            modelRepository = FakeModelRepository(models),
            healthChecker = { healthResult() },
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
            if (callCount == 1) throw RuntimeException("timeout") else HealthResponse("ok")
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
            healthChecker = { HealthResponse("ok") },
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
            healthChecker = {
                readySeenBeforeHealth = vm.state.value is DashboardUiState.Ready
                HealthResponse("ok")
            },
        )
        connFlow.value = connectedState
        assertTrue(readySeenBeforeHealth)

        connFlow.value = GatewayConnection.Disconnected
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
    override suspend fun getModels(): List<ModelInfo> = models
    override suspend fun setActiveModel(modelId: String) = Unit
}
