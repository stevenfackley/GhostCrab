package com.openclaw.ghostcrab.ui

import com.openclaw.ghostcrab.domain.exception.AIQuotaExceededException
import com.openclaw.ghostcrab.domain.exception.AIServiceUnavailableException
import com.openclaw.ghostcrab.domain.exception.GatewayApiException
import com.openclaw.ghostcrab.domain.model.AIRecommendation
import com.openclaw.ghostcrab.domain.model.AuthRequirement
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import com.openclaw.ghostcrab.domain.model.ModelInfo
import com.openclaw.ghostcrab.domain.model.OpenClawConfig
import com.openclaw.ghostcrab.domain.model.RecommendationContext
import com.openclaw.ghostcrab.domain.model.SuggestedChange
import com.openclaw.ghostcrab.domain.repository.AIRecommendationService
import com.openclaw.ghostcrab.domain.repository.ConfigRepository
import com.openclaw.ghostcrab.domain.repository.GatewayConnectionManager
import com.openclaw.ghostcrab.domain.repository.ModelRepository
import com.openclaw.ghostcrab.domain.repository.ScopeProbe
import com.openclaw.ghostcrab.domain.repository.ScopeProbeResult
import io.mockk.coEvery
import io.mockk.mockk
import com.openclaw.ghostcrab.ui.airecommend.AIRecommendationUiState
import com.openclaw.ghostcrab.ui.airecommend.AIRecommendationViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonElement
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
 * Unit tests for [AIRecommendationViewModel].
 *
 * Uses [UnconfinedTestDispatcher] — coroutines in viewModelScope run eagerly,
 * so state transitions are observable synchronously.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AIRecommendationViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeEach fun setUp() { Dispatchers.setMain(testDispatcher) }
    @AfterEach fun tearDown() { Dispatchers.resetMain() }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private val gatewayUrl = "http://192.168.1.50:18789"

    private val connectedWithSkill = GatewayConnection.Connected(
        url = gatewayUrl,
        displayName = "Home Gateway",
        version = "1.0.0",
        authRequirement = AuthRequirement.None,
        isHttps = false,
        capabilities = listOf("skill-ai-recommend"),
        hardwareInfo = "16 GB RAM, RTX 3090",
    )

    private val connectedNoSkill = connectedWithSkill.copy(capabilities = emptyList())

    private fun change(section: String = "models", key: String = "timeout_ms") = SuggestedChange(
        section = section,
        key = key,
        currentValue = "30000",
        suggestedValue = "60000",
        rationale = "Longer timeout for large models",
    )

    private fun recommendation(vararg changes: SuggestedChange) = AIRecommendation(
        query = "best config for coding",
        recommendation = "Increase timeout and use a larger context window.",
        suggestedChanges = changes.toList(),
    )

    private fun makeVm(
        initialConnection: GatewayConnection = connectedWithSkill,
        service: FakeAIService = FakeAIService(available = true),
        config: FakeAIConfigRepository = FakeAIConfigRepository(),
        models: FakeAIModelRepository = FakeAIModelRepository(),
        scopeProbe: ScopeProbe? = null,
    ): Pair<AIRecommendationViewModel, MutableStateFlow<GatewayConnection>> {
        val flow = MutableStateFlow(initialConnection)
        val vm = AIRecommendationViewModel(
            aiService = service,
            connectionManager = FakeAIConnectionManager(flow),
            configRepository = config,
            modelRepository = models,
            scopeProbe = scopeProbe,
        )
        return vm to flow
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `initial state is Idle when connected with skill available`() = runTest {
        val (vm, _) = makeVm(initialConnection = connectedWithSkill)
        assertInstanceOf(AIRecommendationUiState.Idle::class.java, vm.state.value)
    }

    @Test
    fun `initial state is SkillUnavailable when connected without skill`() = runTest {
        val service = FakeAIService(available = false)
        val (vm, _) = makeVm(initialConnection = connectedNoSkill, service = service)
        assertInstanceOf(AIRecommendationUiState.SkillUnavailable::class.java, vm.state.value)
    }

    @Test
    fun `submitQuery transitions to Loading then Ready`() = runTest {
        val rec = recommendation(change())
        val service = FakeAIService(available = true, recommendation = rec)
        val (vm, _) = makeVm(service = service)

        vm.submitQuery("best config for coding")

        val state = assertInstanceOf(AIRecommendationUiState.Ready::class.java, vm.state.value)
        assertEquals(rec.recommendation, state.recommendation.recommendation)
        assertEquals(1, state.recommendation.suggestedChanges.size)
        // All changes selected by default
        assertEquals(state.recommendation.suggestedChanges.toSet(), state.selectedChanges)
    }

    @Test
    fun `submitQuery with AIServiceUnavailableException sets SkillUnavailable`() = runTest {
        val service = FakeAIService(available = true).apply {
            error = AIServiceUnavailableException(gatewayUrl)
        }
        val (vm, _) = makeVm(service = service)

        vm.submitQuery("best model")

        assertInstanceOf(AIRecommendationUiState.SkillUnavailable::class.java, vm.state.value)
    }

    @Test
    fun `submitQuery with AIQuotaExceededException sets Error with quota message`() = runTest {
        val service = FakeAIService(available = true).apply {
            error = AIQuotaExceededException(gatewayUrl)
        }
        val (vm, _) = makeVm(service = service)

        vm.submitQuery("best model")

        val state = assertInstanceOf(AIRecommendationUiState.Error::class.java, vm.state.value)
        assertTrue(state.message.contains("rate limit", ignoreCase = true))
    }

    @Test
    fun `submitQuery with GatewayApiException sets Error with status code`() = runTest {
        val service = FakeAIService(available = true).apply {
            error = GatewayApiException(gatewayUrl, 503)
        }
        val (vm, _) = makeVm(service = service)

        vm.submitQuery("best model")

        val state = assertInstanceOf(AIRecommendationUiState.Error::class.java, vm.state.value)
        assertTrue(state.message.contains("503"))
    }

    @Test
    fun `toggleChange removes a selected change then re-adds it`() = runTest {
        val c = change()
        val rec = recommendation(c)
        val service = FakeAIService(available = true, recommendation = rec)
        val (vm, _) = makeVm(service = service)

        vm.submitQuery("query")

        // Change starts selected
        val readyBefore = vm.state.value as AIRecommendationUiState.Ready
        assertTrue(c in readyBefore.selectedChanges)

        // Toggle off
        vm.toggleChange(c)
        val readyAfterRemove = vm.state.value as AIRecommendationUiState.Ready
        assertFalse(c in readyAfterRemove.selectedChanges)

        // Toggle back on
        vm.toggleChange(c)
        val readyAfterAdd = vm.state.value as AIRecommendationUiState.Ready
        assertTrue(c in readyAfterAdd.selectedChanges)
    }

    @Test
    fun `applySelectedChanges calls updateConfig for each selected change`() = runTest {
        val c1 = change("models", "timeout_ms")
        val c2 = change("gateway", "max_connections")
        val rec = recommendation(c1, c2)
        val service = FakeAIService(available = true, recommendation = rec)
        val configRepo = FakeAIConfigRepository()
        val (vm, _) = makeVm(service = service, config = configRepo)

        vm.submitQuery("query")
        vm.applySelectedChanges()

        val state = vm.state.value as AIRecommendationUiState.Ready
        assertTrue(state.applySuccess)
        assertEquals(2, configRepo.updateCallCount)
    }

    @Test
    fun `applySelectedChanges with only one change selected applies only that one`() = runTest {
        val c1 = change("models", "timeout_ms")
        val c2 = change("gateway", "max_connections")
        val rec = recommendation(c1, c2)
        val service = FakeAIService(available = true, recommendation = rec)
        val configRepo = FakeAIConfigRepository()
        val (vm, _) = makeVm(service = service, config = configRepo)

        vm.submitQuery("query")
        // Deselect c2
        vm.toggleChange(c2)
        vm.applySelectedChanges()

        assertEquals(1, configRepo.updateCallCount)
        assertEquals("models", configRepo.lastSectionUpdated)
    }

    @Test
    fun `reset from Ready returns to Idle`() = runTest {
        val rec = recommendation()
        val service = FakeAIService(available = true, recommendation = rec)
        val (vm, _) = makeVm(service = service)

        vm.submitQuery("query")
        assertInstanceOf(AIRecommendationUiState.Ready::class.java, vm.state.value)

        vm.reset()
        assertInstanceOf(AIRecommendationUiState.Idle::class.java, vm.state.value)
    }

    @Test
    fun `clearApplySuccess clears flag`() = runTest {
        val c = change()
        val rec = recommendation(c)
        val service = FakeAIService(available = true, recommendation = rec)
        val (vm, _) = makeVm(service = service)

        vm.submitQuery("query")
        vm.applySelectedChanges()

        val stateWithSuccess = vm.state.value as AIRecommendationUiState.Ready
        assertTrue(stateWithSuccess.applySuccess)

        vm.clearApplySuccess()
        val stateCleared = vm.state.value as AIRecommendationUiState.Ready
        assertFalse(stateCleared.applySuccess)
    }

    @Test
    fun `applySelectedChanges on empty selection is a no-op`() = runTest {
        val c = change()
        val rec = recommendation(c)
        val service = FakeAIService(available = true, recommendation = rec)
        val configRepo = FakeAIConfigRepository()
        val (vm, _) = makeVm(service = service, config = configRepo)

        vm.submitQuery("query")
        // Deselect the only change
        vm.toggleChange(c)
        vm.applySelectedChanges()

        assertEquals(0, configRepo.updateCallCount)
        val state = vm.state.value as AIRecommendationUiState.Ready
        assertNull(state.applyError)
        assertFalse(state.applySuccess)
    }

    @Test
    fun `AIServiceUnavailable with scope probe reporting missing operator_admin sets missingScope`() = runTest {
        val scopeProbe = mockk<ScopeProbe>()
        coEvery { scopeProbe.probe() } returns ScopeProbeResult.Known(setOf("operator.read"))
        val service = FakeAIService(available = true).apply {
            error = AIServiceUnavailableException(gatewayUrl)
        }

        val (vm, _) = makeVm(service = service, scopeProbe = scopeProbe)
        vm.submitQuery("anything")

        val s = vm.state.value as AIRecommendationUiState.SkillUnavailable
        assertEquals("operator.admin", s.missingScope)
    }
}

// ── Fakes ─────────────────────────────────────────────────────────────────────

private class FakeAIConnectionManager(
    private val flow: MutableStateFlow<GatewayConnection>,
) : GatewayConnectionManager {
    override val connectionState: StateFlow<GatewayConnection> = flow
    override suspend fun probeAuth(url: String) = AuthRequirement.None
    override suspend fun connect(url: String, token: String?) { flow.value = GatewayConnection.Connecting(url) }
    override suspend fun disconnect() { flow.value = GatewayConnection.Disconnected }
}

private class FakeAIService(
    private val available: Boolean,
    private var recommendation: AIRecommendation = AIRecommendation(
        query = "",
        recommendation = "Default recommendation.",
        suggestedChanges = emptyList(),
    ),
) : AIRecommendationService {
    var error: Exception? = null

    override suspend fun isAvailable(): Boolean = available

    override suspend fun getRecommendation(query: String, context: RecommendationContext): AIRecommendation {
        error?.let { throw it }
        return recommendation
    }
}

private class FakeAIConfigRepository : ConfigRepository {
    var updateCallCount = 0
    var lastSectionUpdated: String? = null
    var updateError: Exception? = null

    override suspend fun getConfig(): OpenClawConfig = OpenClawConfig(emptyMap())

    override suspend fun updateConfig(section: String, value: JsonElement) {
        updateError?.let { throw it }
        updateCallCount++
        lastSectionUpdated = section
    }
}

private class FakeAIModelRepository : ModelRepository {
    override suspend fun getModels(): List<ModelInfo> = emptyList()
    override suspend fun setActiveModel(modelId: String) = Unit
}
