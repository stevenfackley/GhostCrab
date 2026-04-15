package com.openclaw.ghostcrab.ui

import com.openclaw.ghostcrab.domain.exception.ConfigValidationException
import com.openclaw.ghostcrab.domain.exception.GatewayApiException
import com.openclaw.ghostcrab.domain.model.AuthRequirement
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import com.openclaw.ghostcrab.domain.model.OpenClawConfig
import com.openclaw.ghostcrab.domain.repository.ConfigRepository
import com.openclaw.ghostcrab.domain.repository.GatewayConnectionManager
import com.openclaw.ghostcrab.ui.config.ConfigEditorUiState
import com.openclaw.ghostcrab.ui.config.ConfigEditorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ConfigEditorViewModel].
 *
 * Uses [UnconfinedTestDispatcher] so coroutines in viewModelScope run eagerly —
 * state transitions happen synchronously in most scenarios.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigEditorViewModelTest {

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
        authRequirement = AuthRequirement.None,
        isHttps = false,
        capabilities = emptyList(),
    )

    private val sampleConfig = OpenClawConfig(
        sections = mapOf(
            "gateway" to JsonObject(
                mapOf("http" to JsonObject(mapOf("port" to JsonPrimitive(18789)))),
            ),
        ),
        etag = "abc123",
    )

    private val sampleEdit: JsonElement =
        JsonObject(mapOf("http" to JsonObject(mapOf("port" to JsonPrimitive(9090)))))

    private fun makeVm(
        initialConnection: GatewayConnection = GatewayConnection.Disconnected,
        repo: FakeConfigRepository = FakeConfigRepository(),
    ): Pair<ConfigEditorViewModel, MutableStateFlow<GatewayConnection>> {
        val connFlow = MutableStateFlow(initialConnection)
        val vm = ConfigEditorViewModel(
            connectionManager = FakeConfigConnectionManager(connFlow),
            configRepository = repo,
        )
        return vm to connFlow
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `initial state with Connected connection loads config and is Ready`() = runTest(testDispatcher) {
        val repo = FakeConfigRepository(configToReturn = sampleConfig)
        val (vm, _) = makeVm(initialConnection = connectedState, repo = repo)
        advanceUntilIdle()

        val state = vm.state.value
        assertInstanceOf(ConfigEditorUiState.Ready::class.java, state)
        assertEquals(sampleConfig, (state as ConfigEditorUiState.Ready).config)
        assertEquals(1, repo.getCallCount)
    }

    @Test
    fun `initial state with Disconnected connection is Disconnected`() = runTest(testDispatcher) {
        val (vm, _) = makeVm(initialConnection = GatewayConnection.Disconnected)
        advanceUntilIdle()

        assertInstanceOf(ConfigEditorUiState.Disconnected::class.java, vm.state.value)
    }

    @Test
    fun `editSection updates pendingChanges`() = runTest(testDispatcher) {
        val repo = FakeConfigRepository(configToReturn = sampleConfig)
        val (vm, _) = makeVm(initialConnection = connectedState, repo = repo)
        advanceUntilIdle()

        vm.editSection("gateway", sampleEdit)

        val state = vm.state.value as ConfigEditorUiState.Ready
        assertEquals(sampleEdit, state.pendingChanges["gateway"])
    }

    @Test
    fun `requestSave sets pendingSaveSection`() = runTest(testDispatcher) {
        val repo = FakeConfigRepository(configToReturn = sampleConfig)
        val (vm, _) = makeVm(initialConnection = connectedState, repo = repo)
        advanceUntilIdle()

        vm.editSection("gateway", sampleEdit)
        vm.requestSave("gateway")

        val state = vm.state.value as ConfigEditorUiState.Ready
        assertEquals("gateway", state.pendingSaveSection)
    }

    @Test
    fun `cancelSave clears pendingSaveSection`() = runTest(testDispatcher) {
        val repo = FakeConfigRepository(configToReturn = sampleConfig)
        val (vm, _) = makeVm(initialConnection = connectedState, repo = repo)
        advanceUntilIdle()

        vm.editSection("gateway", sampleEdit)
        vm.requestSave("gateway")
        vm.cancelSave()

        val state = vm.state.value as ConfigEditorUiState.Ready
        assertNull(state.pendingSaveSection)
    }

    @Test
    fun `confirmSave success reloads config and sets saveSuccess`() = runTest(testDispatcher) {
        val updatedConfig = sampleConfig.copy(etag = "def456")
        val repo = FakeConfigRepository(configToReturn = sampleConfig)
        val (vm, _) = makeVm(initialConnection = connectedState, repo = repo)
        advanceUntilIdle()

        // After first load, switch config to updated version for the post-save reload
        repo.configToReturn = updatedConfig

        vm.editSection("gateway", sampleEdit)
        vm.confirmSave("gateway")
        advanceUntilIdle()

        val state = vm.state.value as ConfigEditorUiState.Ready
        assertTrue(state.saveSuccess)
        assertEquals(updatedConfig, state.config)
    }

    @Test
    fun `confirmSave 412 sets concurrentEditSection and reloads`() = runTest(testDispatcher) {
        val repo = FakeConfigRepository(configToReturn = sampleConfig)
        val (vm, _) = makeVm(initialConnection = connectedState, repo = repo)
        advanceUntilIdle()

        repo.updateError = GatewayApiException("http://192.168.1.50:18789/config/gateway", 412)

        vm.editSection("gateway", sampleEdit)
        vm.confirmSave("gateway")
        advanceUntilIdle()

        val state = vm.state.value as ConfigEditorUiState.Ready
        assertEquals("gateway", state.concurrentEditSection)
    }

    @Test
    fun `confirmSave ConfigValidationException sets fieldError`() = runTest(testDispatcher) {
        val repo = FakeConfigRepository(configToReturn = sampleConfig)
        val (vm, _) = makeVm(initialConnection = connectedState, repo = repo)
        advanceUntilIdle()

        repo.updateError = ConfigValidationException("gateway.http.port", "Must be 1-65535")

        vm.editSection("gateway", sampleEdit)
        vm.confirmSave("gateway")
        advanceUntilIdle()

        val state = vm.state.value as ConfigEditorUiState.Ready
        assertEquals("Must be 1-65535", state.fieldErrors["gateway.http.port"])
    }

    @Test
    fun `discardSection removes pending changes and field errors`() = runTest(testDispatcher) {
        val repo = FakeConfigRepository(configToReturn = sampleConfig)
        val (vm, _) = makeVm(initialConnection = connectedState, repo = repo)
        advanceUntilIdle()

        vm.editSection("gateway", sampleEdit)
        vm.setFieldError("gateway.http.port", "Bad value")
        vm.discardSection("gateway")

        val state = vm.state.value as ConfigEditorUiState.Ready
        assertTrue(state.pendingChanges.isEmpty())
        assertTrue(state.fieldErrors.isEmpty())
    }
}

// ── Fakes ─────────────────────────────────────────────────────────────────────

private class FakeConfigRepository(
    var configToReturn: OpenClawConfig = OpenClawConfig(emptyMap()),
    var updateError: Exception? = null,
    var getCallCount: Int = 0,
) : ConfigRepository {
    override suspend fun getConfig(): OpenClawConfig {
        getCallCount++
        return configToReturn
    }

    override suspend fun updateConfig(section: String, value: JsonElement) {
        updateError?.let { throw it }
    }
}

private class FakeConfigConnectionManager(
    private val flow: MutableStateFlow<GatewayConnection>,
) : GatewayConnectionManager {
    override val connectionState: StateFlow<GatewayConnection> = flow

    override suspend fun probeAuth(url: String) = AuthRequirement.None

    override suspend fun connect(url: String, token: String?) {
        flow.value = GatewayConnection.Connecting(url)
    }

    override suspend fun disconnect() {
        flow.value = GatewayConnection.Disconnected
    }
}
