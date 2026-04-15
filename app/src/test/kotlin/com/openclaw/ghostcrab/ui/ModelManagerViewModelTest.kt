package com.openclaw.ghostcrab.ui

import com.openclaw.ghostcrab.domain.exception.GatewayApiException
import com.openclaw.ghostcrab.domain.model.AuthRequirement
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import com.openclaw.ghostcrab.domain.model.ModelInfo
import com.openclaw.ghostcrab.domain.repository.GatewayConnectionManager
import com.openclaw.ghostcrab.domain.repository.ModelRepository
import com.openclaw.ghostcrab.ui.model.ModelManagerUiState
import com.openclaw.ghostcrab.ui.model.ModelManagerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ModelManagerViewModel].
 *
 * Uses [UnconfinedTestDispatcher] so viewModelScope coroutines run eagerly —
 * all state transitions are observable synchronously after each emission.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelManagerViewModelTest {

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
        repo: FakeMMRepository = FakeMMRepository(),
    ): Pair<ModelManagerViewModel, MutableStateFlow<GatewayConnection>> {
        val connFlow = MutableStateFlow(initialConnection)
        val vm = ModelManagerViewModel(
            connectionManager = FakeModelManagerConnectionManager(connFlow),
            modelRepository = repo,
        )
        return vm to connFlow
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `initial state with Connected connection loads models and is Ready`() = runTest {
        val repo = FakeMMRepository(modelsToReturn = listOf(model("gpt-4o", active = true)))
        val (vm, _) = makeVm(initialConnection = connectedState, repo = repo)

        val state = assertInstanceOf(ModelManagerUiState.Ready::class.java, vm.state.value)
        assertEquals(1, state.models.size)
        assertEquals("gpt-4o", state.models.first().id)
        assertTrue(state.models.first().isActive)
    }

    @Test
    fun `initial state with Disconnected is Disconnected`() = runTest {
        val (vm, _) = makeVm(initialConnection = GatewayConnection.Disconnected)
        assertInstanceOf(ModelManagerUiState.Disconnected::class.java, vm.state.value)
    }

    @Test
    fun `loadModels error sets Error state`() = runTest {
        val repo = FakeMMRepository()
        repo.getError = GatewayApiException("http://example.com", 500)
        val (vm, connFlow) = makeVm(repo = repo)
        connFlow.value = connectedState

        val state = assertInstanceOf(ModelManagerUiState.Error::class.java, vm.state.value)
        assertTrue(state.message.contains("500"))
    }

    @Test
    fun `requestSwap on active model is no-op`() = runTest {
        val activeModel = model("gpt-4o", active = true)
        val repo = FakeMMRepository(modelsToReturn = listOf(activeModel))
        val (vm, _) = makeVm(initialConnection = connectedState, repo = repo)

        val before = vm.state.value as ModelManagerUiState.Ready
        vm.requestSwap("gpt-4o")
        val after = vm.state.value as ModelManagerUiState.Ready

        assertNull(after.pendingSwapId)
        assertEquals(before, after)
    }

    @Test
    fun `requestSwap on inactive model sets pendingSwapId`() = runTest {
        val repo = FakeMMRepository(
            modelsToReturn = listOf(
                model("gpt-4o", active = true),
                model("claude-3-5", active = false),
            ),
        )
        val (vm, _) = makeVm(initialConnection = connectedState, repo = repo)

        vm.requestSwap("claude-3-5")

        val state = vm.state.value as ModelManagerUiState.Ready
        assertEquals("claude-3-5", state.pendingSwapId)
    }

    @Test
    fun `cancelSwap clears pendingSwapId`() = runTest {
        val repo = FakeMMRepository(
            modelsToReturn = listOf(
                model("gpt-4o", active = true),
                model("claude-3-5", active = false),
            ),
        )
        val (vm, _) = makeVm(initialConnection = connectedState, repo = repo)

        vm.requestSwap("claude-3-5")
        vm.cancelSwap()

        val state = vm.state.value as ModelManagerUiState.Ready
        assertNull(state.pendingSwapId)
    }

    @Test
    fun `confirmSwap success reloads models and sets swapSuccess`() = runTest {
        val repo = FakeMMRepository(
            modelsToReturn = listOf(
                model("gpt-4o", active = true),
                model("claude-3-5", active = false),
            ),
        )
        val (vm, _) = makeVm(initialConnection = connectedState, repo = repo)

        // Simulate updated state after swap
        repo.modelsToReturn = listOf(
            model("gpt-4o", active = false),
            model("claude-3-5", active = true),
        )

        vm.confirmSwap("claude-3-5")

        val state = vm.state.value as ModelManagerUiState.Ready
        assertTrue(state.swapSuccess)
        assertTrue(state.models.find { it.id == "claude-3-5" }!!.isActive)
    }

    @Test
    fun `confirmSwap failure surfaces error then recovers to Ready`() = runTest {
        val repo = FakeMMRepository(
            modelsToReturn = listOf(
                model("gpt-4o", active = true),
                model("claude-3-5", active = false),
            ),
        )
        repo.setActiveError = GatewayApiException("http://example.com/api/models/active", 503)
        val (vm, _) = makeVm(initialConnection = connectedState, repo = repo)

        // Collect intermediate states by observing after confirmSwap
        vm.confirmSwap("claude-3-5")

        // After recovery the final state must be Ready (not Error)
        val state = assertInstanceOf(ModelManagerUiState.Ready::class.java, vm.state.value)
        // Models should still be present (recovered from repo)
        assertTrue(state.models.isNotEmpty())
    }

    @Test
    fun `clearSwapSuccess clears swapSuccess flag`() = runTest {
        val repo = FakeMMRepository(
            modelsToReturn = listOf(
                model("gpt-4o", active = true),
                model("claude-3-5", active = false),
            ),
        )
        val (vm, _) = makeVm(initialConnection = connectedState, repo = repo)

        repo.modelsToReturn = listOf(
            model("gpt-4o", active = false),
            model("claude-3-5", active = true),
        )
        vm.confirmSwap("claude-3-5")

        val stateWithSuccess = vm.state.value as ModelManagerUiState.Ready
        assertTrue(stateWithSuccess.swapSuccess)

        vm.clearSwapSuccess()

        val stateAfterClear = vm.state.value as ModelManagerUiState.Ready
        assertTrue(!stateAfterClear.swapSuccess)
    }
}

// ── Fakes ─────────────────────────────────────────────────────────────────────

private class FakeModelManagerConnectionManager(
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

private class FakeMMRepository(
    var modelsToReturn: List<ModelInfo> = emptyList(),
) : ModelRepository {
    var getError: Exception? = null
    var setActiveError: Exception? = null
    var getCallCount = 0

    override suspend fun getModels(): List<ModelInfo> {
        getCallCount++
        getError?.let { throw it }
        return modelsToReturn
    }

    override suspend fun setActiveModel(modelId: String) {
        setActiveError?.let { throw it }
    }
}
