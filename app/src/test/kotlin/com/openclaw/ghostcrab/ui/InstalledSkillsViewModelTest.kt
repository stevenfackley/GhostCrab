package com.openclaw.ghostcrab.ui

import com.openclaw.ghostcrab.domain.model.InstalledSkill
import com.openclaw.ghostcrab.domain.model.SkillInstallProgress
import com.openclaw.ghostcrab.domain.model.SkillSource
import com.openclaw.ghostcrab.domain.repository.InstalledSkillRepository
import com.openclaw.ghostcrab.ui.installedskills.InstalledSkillsUiState
import com.openclaw.ghostcrab.ui.installedskills.InstalledSkillsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
 * Unit tests for [InstalledSkillsViewModel].
 *
 * Uses [UnconfinedTestDispatcher] so `viewModelScope.launch` blocks run eagerly
 * and state transitions are observable inline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InstalledSkillsViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeEach fun setUp() { Dispatchers.setMain(testDispatcher) }
    @AfterEach fun tearDown() { Dispatchers.resetMain() }

    private fun skill(slug: String = "wanng-ide/auto-skill-hunter") = InstalledSkill(
        slug = slug,
        installedVersion = "1.0.0",
        source = SkillSource.ClawHub,
        installedAt = 1_700_000_000L,
    )

    @Test
    fun `init loads from observeInstalled and transitions to Ready`() = runTest {
        val list = listOf(skill("foo/bar"), skill("baz/qux"))
        val repo = FakeInstalledRepo(
            observed = MutableStateFlow(list),
            refreshResult = Result.success(list),
        )

        val vm = InstalledSkillsViewModel(repo)

        val s = assertInstanceOf(InstalledSkillsUiState.Ready::class.java, vm.state.value)
        assertEquals(2, s.skills.size)
        assertEquals(1, repo.refreshCalls)
    }

    @Test
    fun `init with refresh failure and no cached list surfaces errorMessage on Ready`() = runTest {
        val repo = FakeInstalledRepo(
            observed = MutableStateFlow(emptyList()),
            refreshResult = Result.failure(IllegalStateException("offline")),
        )

        val vm = InstalledSkillsViewModel(repo)

        val s = assertInstanceOf(InstalledSkillsUiState.Ready::class.java, vm.state.value)
        assertTrue(s.skills.isEmpty())
        assertEquals("offline", s.errorMessage)
    }

    @Test
    fun `observed list updates reflected in Ready`() = runTest {
        val flow = MutableStateFlow<List<InstalledSkill>>(emptyList())
        val repo = FakeInstalledRepo(observed = flow, refreshResult = Result.success(emptyList()))

        val vm = InstalledSkillsViewModel(repo)

        assertTrue((vm.state.value as InstalledSkillsUiState.Ready).skills.isEmpty())

        flow.value = listOf(skill("a/b"))
        val s = vm.state.value as InstalledSkillsUiState.Ready
        assertEquals(1, s.skills.size)
    }

    @Test
    fun `requestUninstall then cancelUninstall toggles pendingUninstallSlug`() = runTest {
        val repo = FakeInstalledRepo(
            observed = MutableStateFlow(listOf(skill("a/b"))),
            refreshResult = Result.success(listOf(skill("a/b"))),
        )
        val vm = InstalledSkillsViewModel(repo)

        vm.requestUninstall("a/b")
        assertEquals("a/b", (vm.state.value as InstalledSkillsUiState.Ready).pendingUninstallSlug)

        vm.cancelUninstall()
        assertNull((vm.state.value as InstalledSkillsUiState.Ready).pendingUninstallSlug)
    }

    @Test
    fun `confirmUninstall success sets uninstallSuccessSlug and clears dialog`() = runTest {
        val repo = FakeInstalledRepo(
            observed = MutableStateFlow(listOf(skill("a/b"))),
            refreshResult = Result.success(listOf(skill("a/b"))),
        )
        val vm = InstalledSkillsViewModel(repo)

        vm.requestUninstall("a/b")
        vm.confirmUninstall()

        val s = vm.state.value as InstalledSkillsUiState.Ready
        assertNull(s.pendingUninstallSlug)
        assertNull(s.uninstallingSlug)
        assertEquals("a/b", s.uninstallSuccessSlug)
        assertEquals(listOf("a/b"), repo.uninstallCalls)
    }

    @Test
    fun `confirmUninstall failure sets errorMessage`() = runTest {
        val repo = FakeInstalledRepo(
            observed = MutableStateFlow(listOf(skill("a/b"))),
            refreshResult = Result.success(listOf(skill("a/b"))),
            uninstallResult = Result.failure(RuntimeException("boom")),
        )
        val vm = InstalledSkillsViewModel(repo)

        vm.requestUninstall("a/b")
        vm.confirmUninstall()

        val s = vm.state.value as InstalledSkillsUiState.Ready
        assertNull(s.uninstallingSlug)
        assertNull(s.uninstallSuccessSlug)
        assertEquals("boom", s.errorMessage)
    }

    @Test
    fun `confirmUninstall is no-op when no pending slug`() = runTest {
        val repo = FakeInstalledRepo(
            observed = MutableStateFlow(emptyList()),
            refreshResult = Result.success(emptyList()),
        )
        val vm = InstalledSkillsViewModel(repo)

        vm.confirmUninstall()
        assertTrue(repo.uninstallCalls.isEmpty())
    }

    @Test
    fun `clearUninstallSuccess and clearError clear their respective fields`() = runTest {
        val repo = FakeInstalledRepo(
            observed = MutableStateFlow(listOf(skill("a/b"))),
            refreshResult = Result.success(listOf(skill("a/b"))),
        )
        val vm = InstalledSkillsViewModel(repo)

        vm.requestUninstall("a/b")
        vm.confirmUninstall()
        assertNotNull((vm.state.value as InstalledSkillsUiState.Ready).uninstallSuccessSlug)

        vm.clearUninstallSuccess()
        assertNull((vm.state.value as InstalledSkillsUiState.Ready).uninstallSuccessSlug)

        // Simulate an error condition by running a fresh failing uninstall
        val failRepo = FakeInstalledRepo(
            observed = MutableStateFlow(listOf(skill("x/y"))),
            refreshResult = Result.success(listOf(skill("x/y"))),
            uninstallResult = Result.failure(RuntimeException("nope")),
        )
        val vm2 = InstalledSkillsViewModel(failRepo)
        vm2.requestUninstall("x/y")
        vm2.confirmUninstall()
        assertEquals("nope", (vm2.state.value as InstalledSkillsUiState.Ready).errorMessage)

        vm2.clearError()
        assertNull((vm2.state.value as InstalledSkillsUiState.Ready).errorMessage)
    }

    @Test
    fun `refresh success clears isRefreshing`() = runTest {
        val list = listOf(skill("a/b"))
        val repo = FakeInstalledRepo(
            observed = MutableStateFlow(list),
            refreshResult = Result.success(list),
        )
        val vm = InstalledSkillsViewModel(repo)
        val initialCalls = repo.refreshCalls

        vm.refresh()

        val s = vm.state.value as InstalledSkillsUiState.Ready
        assertEquals(false, s.isRefreshing)
        assertEquals(initialCalls + 1, repo.refreshCalls)
    }
}

// ── Fakes ─────────────────────────────────────────────────────────────────────

private class FakeInstalledRepo(
    private val observed: MutableStateFlow<List<InstalledSkill>>,
    private val refreshResult: Result<List<InstalledSkill>>,
    private val uninstallResult: Result<Unit> = Result.success(Unit),
) : InstalledSkillRepository {
    var refreshCalls = 0
    val uninstallCalls = mutableListOf<String>()

    override fun observeInstalled(): Flow<List<InstalledSkill>> = observed
    override suspend fun refreshFromGateway(): Result<List<InstalledSkill>> {
        refreshCalls++
        return refreshResult
    }
    override fun install(slug: String, version: String?): Flow<SkillInstallProgress> = emptyFlow()
    override suspend fun uninstall(slug: String): Result<Unit> {
        uninstallCalls += slug
        return uninstallResult
    }
}
