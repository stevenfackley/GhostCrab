package com.openclaw.ghostcrab.ui

import com.openclaw.ghostcrab.domain.exception.AIQuotaExceededException
import com.openclaw.ghostcrab.domain.exception.AIServiceUnavailableException
import com.openclaw.ghostcrab.domain.exception.GatewayApiException
import com.openclaw.ghostcrab.domain.model.AIRecommendation
import com.openclaw.ghostcrab.domain.model.AuthRequirement
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import com.openclaw.ghostcrab.domain.model.InstalledSkill
import com.openclaw.ghostcrab.domain.model.ModelInfo
import com.openclaw.ghostcrab.domain.model.OpenClawConfig
import com.openclaw.ghostcrab.domain.model.RecommendationContext
import com.openclaw.ghostcrab.domain.model.SkillInstallError
import com.openclaw.ghostcrab.domain.model.SkillInstallProgress
import com.openclaw.ghostcrab.domain.model.SkillSource
import com.openclaw.ghostcrab.domain.model.SuggestedChange
import com.openclaw.ghostcrab.domain.repository.AIRecommendationService
import com.openclaw.ghostcrab.domain.repository.ConfigRepository
import com.openclaw.ghostcrab.domain.repository.GatewayConnectionManager
import com.openclaw.ghostcrab.domain.repository.InstalledSkillRepository
import com.openclaw.ghostcrab.domain.repository.ModelRepository
import com.openclaw.ghostcrab.domain.repository.ScopeProbe
import com.openclaw.ghostcrab.domain.repository.ScopeProbeResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.emptyFlow
import io.mockk.coEvery
import io.mockk.mockk
import com.openclaw.ghostcrab.ui.airecommend.AIRecommendationUiState
import com.openclaw.ghostcrab.ui.airecommend.AIRecommendationViewModel
import kotlinx.coroutines.CompletableDeferred
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
        capabilities = listOf("ai.recommend"),
        hardwareInfo = "16 GB RAM, RTX 3090",
    )

    private val connectedNoSkill = connectedWithSkill.copy(capabilities = emptyList())

    private fun change(
        section: String = "models",
        key: String = "timeout_ms",
        value: String = "60000",
    ) = SuggestedChange(
        section = section,
        key = key,
        currentValue = "30000",
        suggestedValue = value,
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
        installRepo: InstalledSkillRepository? = null,
    ): Pair<AIRecommendationViewModel, MutableStateFlow<GatewayConnection>> {
        val flow = MutableStateFlow(initialConnection)
        val vm = AIRecommendationViewModel(
            aiService = service,
            connectionManager = FakeAIConnectionManager(flow),
            configRepository = config,
            modelRepository = models,
            scopeProbe = scopeProbe,
            installRepo = installRepo,
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
    fun `submitQuery transitions to Loading then Ready with nothing selected`() = runTest {
        val rec = recommendation(change())
        val service = FakeAIService(available = true, recommendation = rec)
        val (vm, _) = makeVm(service = service)

        vm.submitQuery("best config for coding")

        val state = assertInstanceOf(AIRecommendationUiState.Ready::class.java, vm.state.value)
        assertEquals(rec.recommendation, state.recommendation.recommendation)
        assertEquals(1, state.recommendation.suggestedChanges.size)
        assertTrue(state.selectedChanges.isEmpty())
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
    fun `toggleChange selects an unselected change then deselects it`() = runTest {
        val c = change()
        val (vm, _) = makeVm(service = FakeAIService(available = true, recommendation = recommendation(c)))
        vm.submitQuery("query")

        vm.toggleChange(c)
        assertTrue(c in (vm.state.value as AIRecommendationUiState.Ready).selectedChanges)

        vm.toggleChange(c)
        assertFalse(c in (vm.state.value as AIRecommendationUiState.Ready).selectedChanges)
    }

    @Test
    fun `applySelectedChanges calls updateConfig for each selected change`() = runTest {
        val c1 = change("models", "timeout_ms")
        val c2 = change("gateway", "max_connections")
        val service = FakeAIService(available = true, recommendation = recommendation(c1, c2))
        val configRepo = FakeAIConfigRepository()
        val (vm, _) = makeVm(service = service, config = configRepo)

        vm.submitQuery("query")
        vm.toggleChange(c1)
        vm.toggleChange(c2)
        vm.applySelectedChanges()

        assertTrue((vm.state.value as AIRecommendationUiState.Ready).applySuccess)
        assertEquals(2, configRepo.updateCallCount)
    }

    @Test
    fun `applySelectedChanges with only one change selected applies only that one`() = runTest {
        val c1 = change("models", "timeout_ms")
        val c2 = change("gateway", "max_connections")
        val service = FakeAIService(available = true, recommendation = recommendation(c1, c2))
        val configRepo = FakeAIConfigRepository()
        val (vm, _) = makeVm(service = service, config = configRepo)

        vm.submitQuery("query")
        vm.toggleChange(c1)
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
    fun `clearApplyFlags clearSuccess resets applySuccess flag`() = runTest {
        val c = change()
        val rec = recommendation(c)
        val service = FakeAIService(available = true, recommendation = rec)
        val (vm, _) = makeVm(service = service)

        vm.submitQuery("query")
        vm.toggleChange(c)
        vm.applySelectedChanges()

        val stateWithSuccess = vm.state.value as AIRecommendationUiState.Ready
        assertTrue(stateWithSuccess.applySuccess)

        vm.clearApplyFlags(clearSuccess = true)
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

    // ── Install flow ──────────────────────────────────────────────────────────

    @Test
    fun `SkillUnavailable has canInstall=true when installRepo is wired`() = runTest {
        val service = FakeAIService(available = true).apply {
            error = AIServiceUnavailableException(gatewayUrl)
        }
        val repo = FakeInstallRepo()
        val (vm, _) = makeVm(service = service, installRepo = repo)

        vm.submitQuery("query")

        val s = vm.state.value as AIRecommendationUiState.SkillUnavailable
        assertTrue(s.canInstall)
        assertNull(s.missingScope)
    }

    @Test
    fun `SkillUnavailable has canInstall=false when installRepo is null`() = runTest {
        val service = FakeAIService(available = true).apply {
            error = AIServiceUnavailableException(gatewayUrl)
        }
        val (vm, _) = makeVm(service = service, installRepo = null)

        vm.submitQuery("query")

        val s = vm.state.value as AIRecommendationUiState.SkillUnavailable
        assertFalse(s.canInstall)
    }

    @Test
    fun `installSkill is no-op when installRepo is null`() = runTest {
        val service = FakeAIService(available = false)
        val (vm, _) = makeVm(initialConnection = connectedNoSkill, service = service, installRepo = null)

        val before = vm.state.value
        vm.installSkill()
        assertEquals(before, vm.state.value)
    }

    @Test
    fun `installSkill Succeeded with empty lastQuery transitions to Idle`() = runTest {
        val service = FakeAIService(available = false)
        val repo = FakeInstallRepo().apply {
            scripted = flowOf(
                SkillInstallProgress.Connecting("gateway"),
                SkillInstallProgress.Downloading(50),
                SkillInstallProgress.Succeeded(
                    InstalledSkill("wanng-ide/auto-skill-hunter", "1.0.0", SkillSource.ClawHub, 0L),
                ),
            )
        }
        val (vm, _) = makeVm(initialConnection = connectedNoSkill, service = service, installRepo = repo)

        vm.installSkill()

        assertInstanceOf(AIRecommendationUiState.Idle::class.java, vm.state.value)
        assertEquals("wanng-ide/auto-skill-hunter", repo.installedSlug)
    }

    @Test
    fun `installSkill Succeeded with prior query auto-resubmits`() = runTest {
        val rec = recommendation(change())
        val service = FakeAIService(available = true, recommendation = rec).apply {
            error = AIServiceUnavailableException(gatewayUrl)
        }
        val repo = FakeInstallRepo().apply {
            scripted = flow {
                emit(SkillInstallProgress.Downloading(100))
                // Clear service error so auto-resubmit succeeds
                service.error = null
                emit(SkillInstallProgress.Succeeded(
                    InstalledSkill("wanng-ide/auto-skill-hunter", "1.0.0", SkillSource.ClawHub, 0L),
                ))
            }
        }
        val (vm, _) = makeVm(service = service, installRepo = repo)

        vm.submitQuery("longer context")
        assertInstanceOf(AIRecommendationUiState.SkillUnavailable::class.java, vm.state.value)

        vm.installSkill()

        // After Succeeded, VM resubmits lastQuery → Ready
        val ready = assertInstanceOf(AIRecommendationUiState.Ready::class.java, vm.state.value)
        assertEquals("longer context", ready.query)
    }

    @Test
    fun `installSkill Failed Unauthorized flips canInstall false and populates missingScope`() = runTest {
        val service = FakeAIService(available = true).apply {
            error = AIServiceUnavailableException(gatewayUrl)
        }
        val repo = FakeInstallRepo().apply {
            scripted = flowOf(
                SkillInstallProgress.Failed(SkillInstallError.Unauthorized("operator.admin")),
            )
        }
        val (vm, _) = makeVm(service = service, installRepo = repo)

        vm.submitQuery("any")
        vm.installSkill()

        val s = vm.state.value as AIRecommendationUiState.SkillUnavailable
        assertFalse(s.canInstall)
        assertEquals("operator.admin", s.missingScope)
        assertInstanceOf(SkillInstallError.Unauthorized::class.java, s.installError)
        assertNull(s.installProgress)
    }

    @Test
    fun `installSkill Failed Network preserves canInstall and sets retryable error`() = runTest {
        val service = FakeAIService(available = true).apply {
            error = AIServiceUnavailableException(gatewayUrl)
        }
        val repo = FakeInstallRepo().apply {
            scripted = flowOf(SkillInstallProgress.Failed(SkillInstallError.Network("timeout")))
        }
        val (vm, _) = makeVm(service = service, installRepo = repo)

        vm.submitQuery("any")
        vm.installSkill()

        val s = vm.state.value as AIRecommendationUiState.SkillUnavailable
        assertTrue(s.canInstall)
        val err = assertInstanceOf(SkillInstallError.Network::class.java, s.installError)
        assertTrue(err.isRetryable)
    }

    @Test
    fun `dismissInstallError clears installError only`() = runTest {
        val service = FakeAIService(available = true).apply {
            error = AIServiceUnavailableException(gatewayUrl)
        }
        val repo = FakeInstallRepo().apply {
            scripted = flowOf(SkillInstallProgress.Failed(SkillInstallError.Network("x")))
        }
        val (vm, _) = makeVm(service = service, installRepo = repo)

        vm.submitQuery("any")
        vm.installSkill()
        assertNotNull((vm.state.value as AIRecommendationUiState.SkillUnavailable).installError)

        vm.dismissInstallError()
        val s = vm.state.value as AIRecommendationUiState.SkillUnavailable
        assertNull(s.installError)
        assertTrue(s.canInstall)
    }

    @Test
    fun `security-affecting selection is confirmed before anything is written`() = runTest {
        val risky = change("gateway", "auth.mode")
        val configRepo = FakeAIConfigRepository()
        val (vm, _) = makeVm(
            service = FakeAIService(available = true, recommendation = recommendation(risky)),
            config = configRepo,
        )
        vm.submitQuery("query")
        vm.toggleChange(risky)

        vm.applySelectedChanges()
        assertEquals(listOf(risky), (vm.state.value as AIRecommendationUiState.Ready).confirmSecurityChanges)
        assertEquals(0, configRepo.updateCallCount)

        vm.applySelectedChanges(securityConfirmed = true)
        assertEquals(1, configRepo.updateCallCount)
        assertTrue((vm.state.value as AIRecommendationUiState.Ready).confirmSecurityChanges.isEmpty())
    }

    @Test
    fun `dismissSecurityConfirmation applies nothing`() = runTest {
        val risky = change("gateway", "auth.mode")
        val configRepo = FakeAIConfigRepository()
        val (vm, _) = makeVm(
            service = FakeAIService(available = true, recommendation = recommendation(risky)),
            config = configRepo,
        )
        vm.submitQuery("query")
        vm.toggleChange(risky)
        vm.applySelectedChanges()

        vm.dismissSecurityConfirmation()

        assertTrue((vm.state.value as AIRecommendationUiState.Ready).confirmSecurityChanges.isEmpty())
        assertEquals(0, configRepo.updateCallCount)
    }

    @Test
    fun `suggestions echoing the redaction placeholder never reach updateConfig`() = runTest {
        val echoed = change("gateway", "auth.token", "[REDACTED]")
        val echoedObject = change("gateway", "auth", """{"token":"[REDACTED]","x":1}""")
        val configRepo = FakeAIConfigRepository()
        val (vm, _) = makeVm(
            service = FakeAIService(available = true, recommendation = recommendation(echoed, echoedObject)),
            config = configRepo,
        )
        vm.submitQuery("query")
        assertTrue((vm.state.value as AIRecommendationUiState.Ready).recommendation.suggestedChanges.isEmpty())

        vm.toggleChange(echoed)
        vm.toggleChange(echoedObject)
        vm.applySelectedChanges()
        vm.applySelectedChanges(securityConfirmed = true)

        assertEquals(0, configRepo.updateCallCount)
    }

    @Test
    fun `submitQuery counts suggestions dropped for echoing the placeholder`() = runTest {
        val echoed = change("gateway", "auth.token", "[REDACTED]")
        val safe = change()
        val (vm, _) = makeVm(
            service = FakeAIService(available = true, recommendation = recommendation(echoed, safe)),
        )

        vm.submitQuery("query")

        val ready = vm.state.value as AIRecommendationUiState.Ready
        assertEquals(listOf(safe), ready.recommendation.suggestedChanges)
        assertEquals(1, ready.droppedSuggestionCount)
    }

    @Test
    fun `applySelectedChanges ignores a second tap while an apply is in flight`() = runTest {
        val c = change()
        val gate = CompletableDeferred<Unit>()
        val configRepo = FakeAIConfigRepository().apply { this.gate = gate }
        val (vm, _) = makeVm(
            service = FakeAIService(available = true, recommendation = recommendation(c)),
            config = configRepo,
        )
        vm.submitQuery("query")
        vm.toggleChange(c)

        vm.applySelectedChanges(securityConfirmed = true)
        vm.applySelectedChanges(securityConfirmed = true)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, configRepo.updateCallCount)
    }

    @Test
    fun `apply sends one patch per section and reconciles after each write`() = runTest {
        val c1 = change("gateway", "http.port")
        val c2 = change("gateway", "max_connections")
        val c3 = change("models", "timeout_ms")
        val configRepo = FakeAIConfigRepository()
        val (vm, _) = makeVm(
            service = FakeAIService(available = true, recommendation = recommendation(c1, c2, c3)),
            config = configRepo,
        )
        vm.submitQuery("query")
        listOf(c1, c2, c3).forEach(vm::toggleChange)
        val readsBeforeApply = configRepo.getConfigCalls

        vm.applySelectedChanges()

        assertEquals(listOf("gateway", "models"), configRepo.updatedSections)
        assertEquals(2, configRepo.getConfigCalls - readsBeforeApply)
        assertTrue((vm.state.value as AIRecommendationUiState.Ready).applySuccess)
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
    var gate: CompletableDeferred<Unit>? = null
    val updatedSections = mutableListOf<String>()
    var getConfigCalls = 0

    override suspend fun getConfig(): OpenClawConfig {
        getConfigCalls++
        return OpenClawConfig(emptyMap())
    }

    override suspend fun updateConfig(section: String, value: JsonElement) {
        gate?.await()
        updateError?.let { throw it }
        updateCallCount++
        lastSectionUpdated = section
        updatedSections += section
    }
}

private class FakeAIModelRepository : ModelRepository {
    override suspend fun getModels(): List<ModelInfo> = emptyList()
    override suspend fun setActiveModel(modelId: String) = Unit
}

private class FakeInstallRepo : InstalledSkillRepository {
    var scripted: Flow<SkillInstallProgress> = emptyFlow()
    var installedSlug: String? = null

    override fun observeInstalled() = flowOf(emptyList<InstalledSkill>())
    override suspend fun refreshFromGateway(): Result<List<InstalledSkill>> = Result.success(emptyList())
    override fun install(slug: String, version: String?): Flow<SkillInstallProgress> {
        installedSlug = slug
        return scripted
    }
    override suspend fun uninstall(slug: String): Result<Unit> = Result.success(Unit)
}
