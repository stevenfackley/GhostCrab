package com.openclaw.ghostcrab.ui

import app.cash.turbine.test
import com.openclaw.ghostcrab.domain.model.ConnectionProfile
import com.openclaw.ghostcrab.domain.model.DiscoveredGateway
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import com.openclaw.ghostcrab.domain.repository.ConnectionProfileRepository
import com.openclaw.ghostcrab.domain.repository.DiscoveryService
import com.openclaw.ghostcrab.domain.repository.GatewayConnectionManager
import com.openclaw.ghostcrab.ui.connection.ScanEvent
import com.openclaw.ghostcrab.ui.connection.ScanState
import com.openclaw.ghostcrab.ui.connection.ScanViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScanViewModelTest {

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

    private fun gateway(name: String = "gw1", host: String = "192.168.1.50", port: Int = 18789) =
        DiscoveredGateway(
            instanceName = name,
            hostAddress = host,
            port = port,
            displayName = name,
            version = "1.0.0",
        )

    private fun profile(url: String) = ConnectionProfile(
        id = "id-1",
        displayName = "My Gateway",
        url = url,
        lastConnectedAt = null,
        hasToken = false,
    )

    private fun makeVm(
        service: DiscoveryService = FakeDiscoveryService(),
        profiles: List<ConnectionProfile> = emptyList(),
        connectResult: Result<Unit> = Result.success(Unit),
    ) = ScanViewModel(
        discoveryService = service,
        profileRepository = FakeProfileRepository(profiles),
        connectionManager = FakeConnectionManager(connectResult),
    )

    // ── State machine tests ───────────────────────────────────────────────────

    @Test
    fun `initial state is Idle`() {
        val vm = makeVm()
        assertInstanceOf(ScanState.Idle::class.java, vm.state.value)
    }

    @Test
    fun `startScan transitions to Scanning immediately`() = runTest {
        val vm = makeVm(FakeDiscoveryService(block = true)) // never emits, never completes
        vm.startScan()
        assertInstanceOf(ScanState.Scanning::class.java, vm.state.value)
    }

    @Test
    fun `discovered gateways accumulate in Results`() = runTest {
        val gw1 = gateway("gw1")
        val gw2 = gateway("gw2", host = "192.168.1.51")
        val vm = makeVm(FakeDiscoveryService(gw1, gw2))

        vm.state.test {
            assertEquals(ScanState.Idle, awaitItem())
            vm.startScan()
            assertInstanceOf(ScanState.Scanning::class.java, awaitItem())
            val partial = awaitItem() as ScanState.Results
            assertTrue(partial.gateways.contains(gw1))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `scan completes with scanCompleted=true when flow ends`() = runTest {
        val gw = gateway()
        val vm = makeVm(FakeDiscoveryService(gw))

        vm.startScan()
        advanceUntilIdle()

        val state = vm.state.value as ScanState.Results
        assertTrue(state.scanCompleted)
        assertEquals(listOf(gw), state.gateways)
    }

    @Test
    fun `scan completes empty when no gateways found`() = runTest {
        val vm = makeVm(FakeDiscoveryService()) // emits nothing

        vm.startScan()
        advanceUntilIdle()

        val state = vm.state.value as ScanState.Results
        assertTrue(state.scanCompleted)
        assertTrue(state.gateways.isEmpty())
    }

    @Test
    fun `discovery error transitions to Error state`() = runTest {
        val vm = makeVm(FakeDiscoveryService(failMessage = "NSD start failed: errorCode=0"))

        vm.startScan()
        advanceUntilIdle()

        val state = vm.state.value as ScanState.Error
        assertTrue(state.reason.contains("NSD start failed"))
    }

    @Test
    fun `startScan again resets to Scanning and replaces prior results`() = runTest {
        val vm = makeVm(FakeDiscoveryService(gateway()))

        // First scan completes
        vm.startScan()
        advanceUntilIdle()
        assertTrue((vm.state.value as ScanState.Results).scanCompleted)

        // Start turbine RIGHT before second scan so we capture the Scanning transition
        vm.state.test {
            awaitItem() // current: Results(completed=true)
            vm.startScan()
            assertInstanceOf(ScanState.Scanning::class.java, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Selection tests ───────────────────────────────────────────────────────

    @Test
    fun `selecting unknown gateway emits NavigateToManualEntry with URL`() = runTest {
        val gw = gateway()
        val vm = makeVm()

        vm.events.test {
            vm.onGatewaySelected(gw)
            val event = awaitItem() as ScanEvent.NavigateToManualEntry
            assertEquals(gw.url, event.prefillUrl)
        }
    }

    @Test
    fun `selecting known gateway connects and emits NavigateToDashboard`() = runTest {
        val gw = gateway()
        val vm = makeVm(profiles = listOf(profile(gw.url)))

        vm.events.test {
            vm.onGatewaySelected(gw)
            assertEquals(ScanEvent.NavigateToDashboard, awaitItem())
        }
    }

    @Test
    fun `selecting known gateway emits Error on connection failure`() = runTest {
        val gw = gateway()
        val vm = makeVm(
            profiles = listOf(profile(gw.url)),
            connectResult = Result.failure(RuntimeException("timeout")),
        )

        vm.onGatewaySelected(gw)
        advanceUntilIdle()

        val state = vm.state.value as ScanState.Error
        assertTrue(state.reason.contains("timeout"))
    }
}

// ── Fakes ─────────────────────────────────────────────────────────────────────

/**
 * Synchronous fake that emits [gateways] immediately then completes.
 *
 * @param block When `true`, the flow never completes (simulates an active scan).
 * @param failMessage When non-null, the flow throws with this message instead of emitting.
 */
private class FakeDiscoveryService(
    private vararg val gateways: DiscoveredGateway,
    private val block: Boolean = false,
    private val failMessage: String? = null,
) : DiscoveryService {

    override fun startDiscovery(): Flow<DiscoveredGateway> = flow {
        failMessage?.let { throw IllegalStateException(it) }
        for (gw in gateways) emit(gw)
        if (block) kotlinx.coroutines.awaitCancellation()
    }

    override suspend fun stopDiscovery() {}
}

private class FakeProfileRepository(
    private val profiles: List<ConnectionProfile> = emptyList(),
) : ConnectionProfileRepository {
    override fun getProfiles(): Flow<List<ConnectionProfile>> = flowOf(profiles)
    override suspend fun saveProfile(profile: ConnectionProfile, token: String?) {}
    override suspend fun getToken(profileId: String): String? = null
    override suspend fun deleteProfile(profileId: String) {}
}

private class FakeConnectionManager(
    private val connectResult: Result<Unit> = Result.success(Unit),
) : GatewayConnectionManager {
    override val connectionState: StateFlow<GatewayConnection> =
        MutableStateFlow(GatewayConnection.Disconnected)

    override suspend fun probeAuth(url: String) =
        com.openclaw.ghostcrab.domain.model.AuthRequirement.None

    override suspend fun connect(url: String, token: String?) {
        connectResult.getOrThrow()
    }

    override suspend fun disconnect() {}
}
