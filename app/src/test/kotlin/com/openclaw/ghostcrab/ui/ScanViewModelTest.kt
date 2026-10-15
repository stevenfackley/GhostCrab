package com.openclaw.ghostcrab.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.cash.turbine.test
import com.openclaw.ghostcrab.domain.exception.GatewayException
import com.openclaw.ghostcrab.domain.exception.GatewayPairingRequiredException
import com.openclaw.ghostcrab.domain.exception.GatewayTimeoutException
import com.openclaw.ghostcrab.domain.exception.ProfileNeedsReauthException
import com.openclaw.ghostcrab.domain.model.AuthRequirement
import com.openclaw.ghostcrab.domain.model.ConnectionProfile
import com.openclaw.ghostcrab.domain.model.DiscoveredGateway
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import com.openclaw.ghostcrab.domain.repository.ConnectionProfileRepository
import com.openclaw.ghostcrab.domain.repository.DiscoveryService
import com.openclaw.ghostcrab.domain.repository.GatewayConnectionManager
import com.openclaw.ghostcrab.ui.connection.PAIRING_RETRY_INTERVAL_MS
import com.openclaw.ghostcrab.ui.connection.PAIRING_TIMEOUT_MS
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
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
            connectResult = Result.failure(GatewayTimeoutException(gw.url)),
        )

        vm.onGatewaySelected(gw)
        advanceUntilIdle()

        val state = vm.state.value as ScanState.Error
        assertTrue(state.reason.contains("timed out"))
    }

    @Test
    fun `known gateway with unreadable token emits Error instead of crashing`() = runTest {
        val gw = gateway()
        val vm = ScanViewModel(
            discoveryService = FakeDiscoveryService(),
            profileRepository = FakeProfileRepository(
                profiles = listOf(profile(gw.url).copy(hasToken = true)),
                tokenError = ProfileNeedsReauthException("id-1"),
            ),
            connectionManager = FakeConnectionManager(),
        )

        vm.onGatewaySelected(gw)
        advanceUntilIdle()

        val state = vm.state.value as ScanState.Error
        assertTrue(state.reason.contains("id-1"))
    }

    @Test
    fun `known gateway without token never touches the keystore`() = runTest {
        val gw = gateway()
        val repo = FakeProfileRepository(
            profiles = listOf(profile(gw.url)), // hasToken = false
            tokenError = ProfileNeedsReauthException("id-1"),
        )
        val vm = ScanViewModel(FakeDiscoveryService(), repo, FakeConnectionManager())

        vm.events.test {
            vm.onGatewaySelected(gw)
            assertEquals(ScanEvent.NavigateToDashboard, awaitItem())
        }
        assertEquals(0, repo.getTokenCalls)
    }

    @Test
    fun `navigation event sent while nobody collects is delivered later`() = runTest {
        val vm = makeVm()
        vm.onGatewaySelected(gateway()) // no collector attached, e.g. mid-rotation

        vm.events.test {
            assertInstanceOf(ScanEvent.NavigateToManualEntry::class.java, awaitItem())
        }
    }

    // ── Pairing approval of a saved gateway (ADR-0007) ────────────────────────

    private fun savedGatewayVm(manager: GatewayConnectionManager): Pair<ScanViewModel, DiscoveredGateway> {
        val gw = gateway()
        return ScanViewModel(FakeDiscoveryService(), FakeProfileRepository(listOf(profile(gw.url))), manager) to gw
    }

    /** Clears [viewModel] the way leaving the screen does: `viewModelScope` is cancelled, then its closeables run. */
    private fun clear(viewModel: ScanViewModel) {
        val store = ViewModelStore()
        ViewModelProvider(store, viewModelFactory { initializer { viewModel } })[ScanViewModel::class.java]
        store.clear()
    }

    @Test
    fun `a saved gateway that needs approval shows the panel and opens the dashboard once approved`() = runTest {
        val manager = FakeScanPairingManager(refusals = 1)
        val (vm, gw) = savedGatewayVm(manager)

        vm.events.test {
            vm.onGatewaySelected(gw)
            assertEquals("req-9", vm.pairingPrompt.value?.requestId)
            advanceTimeBy(PAIRING_RETRY_INTERVAL_MS + 1)
            assertEquals(ScanEvent.NavigateToDashboard, awaitItem())
        }
        assertNull(vm.pairingPrompt.value)
        assertEquals(2, manager.calls)
    }

    @Test
    fun `a pairing request is not shown as a scan error`() = runTest {
        val manager = FakeScanPairingManager()
        val (vm, gw) = savedGatewayVm(manager)
        vm.startScan()
        advanceUntilIdle()

        vm.onGatewaySelected(gw)

        assertInstanceOf(ScanState.Results::class.java, vm.state.value)
        vm.pairing.cancel()
    }

    @Test
    fun `Cancel on the panel clears it, stops the retries and disconnects`() = runTest {
        val manager = FakeScanPairingManager()
        val (vm, gw) = savedGatewayVm(manager)
        vm.onGatewaySelected(gw)

        vm.pairing.cancel()
        advanceTimeBy(PAIRING_RETRY_INTERVAL_MS * 10)

        assertNull(vm.pairingPrompt.value)
        assertEquals(1, manager.calls)
        assertEquals(1, manager.disconnects)
        assertEquals(GatewayConnection.Disconnected, manager.state.value)
    }

    @Test
    fun `Check now retries without waiting`() = runTest {
        val manager = FakeScanPairingManager(refusals = 1)
        val (vm, gw) = savedGatewayVm(manager)
        vm.onGatewaySelected(gw)

        vm.pairing.checkNow()
        runCurrent()

        assertEquals(2, manager.calls)
        assertNull(vm.pairingPrompt.value)
    }

    @Test
    fun `a second tap while waiting for approval starts no second loop`() = runTest {
        val manager = FakeScanPairingManager()
        val (vm, gw) = savedGatewayVm(manager)
        vm.onGatewaySelected(gw)
        vm.onGatewaySelected(gw)
        advanceTimeBy(PAIRING_RETRY_INTERVAL_MS + 1)

        assertEquals(2, manager.calls)
        vm.pairing.cancel()
    }

    @Test
    fun `an expired approval window is an explicit error and leaves no pairing error behind`() = runTest {
        val manager = FakeScanPairingManager()
        val (vm, gw) = savedGatewayVm(manager)
        vm.onGatewaySelected(gw)

        advanceTimeBy(PAIRING_TIMEOUT_MS + 1)

        assertNull(vm.pairingPrompt.value)
        assertTrue((vm.state.value as ScanState.Error).reason.contains("PAIRING_REQUIRED"))
        assertEquals(GatewayConnection.Disconnected, manager.state.value)
    }

    @Test
    fun `leaving the screen mid-wait stops the retries and disconnects`() = runTest {
        val manager = FakeScanPairingManager()
        val (vm, gw) = savedGatewayVm(manager)
        vm.onGatewaySelected(gw)

        clear(vm)
        advanceTimeBy(PAIRING_RETRY_INTERVAL_MS * 10)

        assertEquals(1, manager.calls)
        assertEquals(GatewayConnection.Disconnected, manager.state.value)
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
    private val tokenError: GatewayException? = null,
) : ConnectionProfileRepository {
    var getTokenCalls = 0
    override fun getProfiles(): Flow<List<ConnectionProfile>> = flowOf(profiles)
    override suspend fun saveProfile(profile: ConnectionProfile, token: String?) {}
    override suspend fun getToken(profileId: String): String? {
        getTokenCalls++
        tokenError?.let { throw it }
        return null
    }
    override suspend fun deleteProfile(profileId: String) {}
    override suspend fun clearAll() {}
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

/**
 * Refuses the first [refusals] connects for approval (every connect when `null`), like the real manager: it
 * publishes the `PAIRING_REQUIRED` Error for the URL, then throws the pairing request.
 */
private class FakeScanPairingManager(private val refusals: Int? = null) : GatewayConnectionManager {
    val state = MutableStateFlow<GatewayConnection>(GatewayConnection.Disconnected)
    var calls = 0
        private set
    var disconnects = 0
        private set

    override val connectionState: StateFlow<GatewayConnection> = state
    override suspend fun probeAuth(url: String) = AuthRequirement.None

    override suspend fun connect(url: String, token: String?) {
        calls++
        if (refusals == null || calls <= refusals) {
            val refusal = GatewayPairingRequiredException("ws://gw/ws", "req-9", "not-paired", emptyList())
            state.value = GatewayConnection.Error(url, refusal)
            throw refusal
        }
        state.value = GatewayConnection.Connected(
            url = url,
            displayName = "gw1",
            version = "2026.10.3",
            authRequirement = AuthRequirement.Token,
            isHttps = false,
            capabilities = emptyList(),
        )
    }

    override suspend fun disconnect() {
        disconnects++
        state.value = GatewayConnection.Disconnected
    }
}
