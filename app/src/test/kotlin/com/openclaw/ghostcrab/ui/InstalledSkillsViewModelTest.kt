package com.openclaw.ghostcrab.ui

import com.openclaw.ghostcrab.domain.exception.GatewayConnectionClosedException
import com.openclaw.ghostcrab.domain.exception.GatewayNotConnectedException
import com.openclaw.ghostcrab.domain.exception.GatewayTimeoutException
import com.openclaw.ghostcrab.domain.exception.GatewayUnreachableException
import com.openclaw.ghostcrab.domain.model.AuthRequirement
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import com.openclaw.ghostcrab.domain.model.InstalledSkill
import com.openclaw.ghostcrab.domain.model.SkillInstallProgress
import com.openclaw.ghostcrab.domain.model.SkillSource
import com.openclaw.ghostcrab.domain.repository.GatewayConnectionManager
import com.openclaw.ghostcrab.domain.repository.InstalledSkillRepository
import com.openclaw.ghostcrab.ui.installedskills.InstalledSkillsUiState
import com.openclaw.ghostcrab.ui.installedskills.InstalledSkillsViewModel
import com.openclaw.ghostcrab.ui.installedskills.SOCKET_END_GRACE_MS
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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

    private fun session(generation: Long = 1L, reconnecting: Boolean = false) = GatewayConnection.Connected(
        url = GATEWAY_URL,
        displayName = "Basement",
        version = "2026.10.3",
        authRequirement = AuthRequirement.Token,
        isHttps = false,
        capabilities = listOf("skills"),
        reconnecting = reconnecting,
        sessionGeneration = generation,
    )

    private val reconnectFailed = GatewayConnection.Error(GATEWAY_URL, GatewayUnreachableException(GATEWAY_URL))

    /** A gateway with no skills installed. */
    private fun emptyRepo() =
        FakeInstalledRepo(observed = MutableStateFlow(emptyList()), refreshResult = Result.success(emptyList()))

    private fun vm(
        repo: FakeInstalledRepo,
        connection: MutableStateFlow<GatewayConnection> = MutableStateFlow(session()),
    ) = InstalledSkillsViewModel(repo, FakeSkillsConnectionManager(connection))

    /** Every state [vm] emits from now on, in order (not only the last). */
    private fun TestScope.statesOf(vm: InstalledSkillsViewModel): List<InstalledSkillsUiState> {
        val seen = mutableListOf<InstalledSkillsUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.toList(seen) }
        return seen
    }

    /** Neither "Couldn't load skills" nor a snackbar error was ever emitted. */
    private fun assertNoFailureShown(seen: List<InstalledSkillsUiState>) {
        assertTrue(seen.isNotEmpty(), "no state was recorded")
        val failures = seen.filter {
            it is InstalledSkillsUiState.LoadFailed || (it as? InstalledSkillsUiState.Ready)?.errorMessage != null
        }
        assertTrue(failures.isEmpty(), "a failure was shown in between: $failures (all: $seen)")
    }

    // ── Connected: loaded, loading, empty, failed ─────────────────────────────

    @Test
    fun `init loads from observeInstalled and transitions to Ready`() = runTest {
        val list = listOf(skill("foo/bar"), skill("baz/qux"))
        val repo = FakeInstalledRepo(
            observed = MutableStateFlow(list),
            refreshResult = Result.success(list),
        )

        val vm = vm(repo)

        val s = assertInstanceOf(InstalledSkillsUiState.Ready::class.java, vm.state.value)
        assertEquals(2, s.skills.size)
        assertEquals(1, repo.refreshCalls)
    }

    @Test
    fun `an empty list read on a live session says no skills installed`() = runTest {
        val repo = emptyRepo()

        val vm = vm(repo)

        val s = assertInstanceOf(InstalledSkillsUiState.Ready::class.java, vm.state.value)
        assertTrue(s.skills.isEmpty())
        assertNull(s.errorMessage)
    }

    @Test
    fun `the spinner shows while the first read is in flight and ends with its answer`() = runTest {
        val list = listOf(skill("a/b"))
        val repo = emptyRepo()
        val answer = repo.holdNextRead()

        val vm = vm(repo)
        // The not-yet-read empty list is not "no skills installed".
        assertSame(InstalledSkillsUiState.Loading, vm.state.value)

        answer.complete(Result.success(list))
        assertEquals(list, assertInstanceOf(InstalledSkillsUiState.Ready::class.java, vm.state.value).skills)
    }

    @Test
    fun `a timeout on a live socket shows the failure at once`() = runTest {
        val repo = emptyRepo()
        repo.refreshResult = Result.failure(GatewayTimeoutException("$GATEWAY_URL#skills.status"))

        val vm = vm(repo)

        val s = assertInstanceOf(InstalledSkillsUiState.LoadFailed::class.java, vm.state.value)
        assertEquals("Gateway request timed out at $GATEWAY_URL#skills.status (GatewayTimeoutException)", s.message)
    }

    @Test
    fun `a first read that fails on a live session is a load failure, not an empty list`() = runTest {
        val repo = FakeInstalledRepo(
            observed = MutableStateFlow(emptyList()),
            refreshResult = Result.failure(IllegalStateException("offline")),
        )

        val vm = vm(repo)

        val s = assertInstanceOf(InstalledSkillsUiState.LoadFailed::class.java, vm.state.value)
        assertEquals("offline (IllegalStateException)", s.message)
    }

    @Test
    fun `retry after a failed first read loads the list`() = runTest {
        val repo = FakeInstalledRepo(
            observed = MutableStateFlow(emptyList()),
            refreshResult = Result.failure(IllegalStateException("offline")),
        )
        val vm = vm(repo)
        assertInstanceOf(InstalledSkillsUiState.LoadFailed::class.java, vm.state.value)

        repo.refreshResult = Result.success(listOf(skill("a/b")))
        vm.refresh()

        assertEquals(1, assertInstanceOf(InstalledSkillsUiState.Ready::class.java, vm.state.value).skills.size)
        assertEquals(2, repo.refreshCalls)
    }

    @Test
    fun `a failed refresh with the list showing keeps it and reports the error`() = runTest {
        val list = listOf(skill("a/b"))
        val repo = FakeInstalledRepo(observed = MutableStateFlow(list), refreshResult = Result.success(list))
        val vm = vm(repo)

        repo.refreshResult = Result.failure(IllegalStateException("offline"))
        vm.refresh()

        val s = assertInstanceOf(InstalledSkillsUiState.Ready::class.java, vm.state.value)
        assertEquals(list, s.skills)
        assertEquals(false, s.isRefreshing)
        assertEquals("offline (IllegalStateException)", s.errorMessage)
    }

    @Test
    fun `the live session's cached list shows at once while the read runs`() = runTest {
        val list = listOf(skill("a/b"))
        val repo = FakeInstalledRepo(observed = MutableStateFlow(list), refreshResult = Result.success(list))
        val answer = repo.holdNextRead()

        val vm = vm(repo)

        val s = assertInstanceOf(InstalledSkillsUiState.Ready::class.java, vm.state.value)
        assertEquals(list, s.skills)
        assertTrue(s.isRefreshing)
        answer.complete(Result.success(list))
        assertEquals(false, (vm.state.value as InstalledSkillsUiState.Ready).isRefreshing)
    }

    @Test
    fun `observed list updates reflected in Ready`() = runTest {
        val flow = MutableStateFlow<List<InstalledSkill>>(emptyList())
        val repo = FakeInstalledRepo(observed = flow, refreshResult = Result.success(emptyList()))

        val vm = vm(repo)

        assertTrue((vm.state.value as InstalledSkillsUiState.Ready).skills.isEmpty())

        flow.value = listOf(skill("a/b"))
        val s = vm.state.value as InstalledSkillsUiState.Ready
        assertEquals(1, s.skills.size)
    }

    // ── Reconnecting, reconnect failed, never connected ───────────────────────

    @Test
    fun `opened during the silent reconnect it shows the spinner and sends no read`() = runTest {
        val repo = emptyRepo()

        val vm = vm(repo, MutableStateFlow(session(reconnecting = true)))

        assertSame(InstalledSkillsUiState.Loading, vm.state.value)
        assertEquals(0, repo.refreshCalls)
    }

    @Test
    fun `a failed silent reconnect ends the spinner with Disconnected and its reason`() = runTest {
        val connection = MutableStateFlow<GatewayConnection>(session(reconnecting = true))
        val repo = emptyRepo()
        val vm = vm(repo, connection)

        connection.value = reconnectFailed

        val s = assertInstanceOf(InstalledSkillsUiState.Disconnected::class.java, vm.state.value)
        assertEquals("Gateway unreachable at $GATEWAY_URL (GatewayUnreachableException)", s.reason)
        assertEquals(0, repo.refreshCalls)
    }

    @Test
    fun `the rows stay during the silent reconnect and give way to Disconnected when it fails`() = runTest {
        val list = listOf(skill("a/b"))
        val connection = MutableStateFlow<GatewayConnection>(session())
        val repo = FakeInstalledRepo(observed = MutableStateFlow(list), refreshResult = Result.success(list))
        val vm = vm(repo, connection)
        val ready = vm.state.value

        connection.value = session(reconnecting = true)
        assertSame(ready, vm.state.value)

        connection.value = reconnectFailed
        assertInstanceOf(InstalledSkillsUiState.Disconnected::class.java, vm.state.value)
        assertEquals(1, repo.refreshCalls)
    }

    @Test
    fun `a list emitted while no session is live does not replace the rows`() = runTest {
        val list = listOf(skill("a/b"))
        val observed = MutableStateFlow(list)
        val connection = MutableStateFlow<GatewayConnection>(session())
        val repo = FakeInstalledRepo(observed = observed, refreshResult = Result.success(list))
        val vm = vm(repo, connection)

        connection.value = session(reconnecting = true)
        // What the repository emits for a socket that is no longer live (e.g. an install's stand-in entry).
        observed.value = emptyList()

        assertEquals(list, assertInstanceOf(InstalledSkillsUiState.Ready::class.java, vm.state.value).skills)
    }

    @Test
    fun `a read still in flight when the reconnect fails does not bring a list back`() = runTest {
        val connection = MutableStateFlow<GatewayConnection>(session())
        val repo = emptyRepo()
        val answer = repo.holdNextRead()
        val vm = vm(repo, connection)

        connection.value = session(reconnecting = true)
        assertSame(InstalledSkillsUiState.Loading, vm.state.value)
        connection.value = reconnectFailed
        answer.complete(Result.success(listOf(skill("a/b"))))

        assertInstanceOf(InstalledSkillsUiState.Disconnected::class.java, vm.state.value)
    }

    @Test
    fun `reconnecting seen before the read fails - the failure is not shown`() = runTest {
        val connection = MutableStateFlow<GatewayConnection>(session())
        val repo = emptyRepo()
        val answer = repo.holdNextRead()
        val vm = vm(repo, connection)
        val seen = statesOf(vm)

        connection.value = session(reconnecting = true)
        answer.complete(Result.failure(GatewayNotConnectedException(reconnectingTo = GATEWAY_URL)))

        assertSame(InstalledSkillsUiState.Loading, vm.state.value)
        assertNoFailureShown(seen)
    }

    // ── Production order: the socket's end fails the read first, the manager publishes reconnecting after ──

    @Test
    fun `nothing loaded - read fails at the drop, then reconnecting, then the reconnect succeeds`() = runTest {
        val list = listOf(skill("a/b"))
        val connection = MutableStateFlow<GatewayConnection>(session())
        val repo = emptyRepo()
        val first = repo.holdNextRead()
        val vm = vm(repo, connection)
        val seen = statesOf(vm)

        first.complete(Result.failure(GatewayUnreachableException(GATEWAY_URL)))
        assertSame(InstalledSkillsUiState.Loading, vm.state.value)
        connection.value = session(reconnecting = true)
        assertSame(InstalledSkillsUiState.Loading, vm.state.value)
        repo.refreshResult = Result.success(list)
        connection.value = session(generation = 2L)

        assertEquals(list, assertInstanceOf(InstalledSkillsUiState.Ready::class.java, vm.state.value).skills)
        assertNoFailureShown(seen)
        assertEquals(2, repo.refreshCalls)
    }

    @Test
    fun `nothing loaded - read fails at the drop, then reconnecting, then the reconnect fails`() = runTest {
        val connection = MutableStateFlow<GatewayConnection>(session())
        val repo = emptyRepo()
        val first = repo.holdNextRead()
        val vm = vm(repo, connection)
        val seen = statesOf(vm)

        first.complete(Result.failure(GatewayConnectionClosedException(GATEWAY_URL, 1008, "")))
        assertSame(InstalledSkillsUiState.Loading, vm.state.value)
        connection.value = session(reconnecting = true)
        connection.value = reconnectFailed

        assertInstanceOf(InstalledSkillsUiState.Disconnected::class.java, vm.state.value)
        assertNoFailureShown(seen)
        assertEquals(1, repo.refreshCalls)
    }

    @Test
    fun `rows on screen - refresh fails at the drop, then reconnecting, then the reconnect succeeds`() = runTest {
        val before = listOf(skill("a/b"))
        val after = listOf(skill("a/b"), skill("c/d"))
        val connection = MutableStateFlow<GatewayConnection>(session())
        val repo = FakeInstalledRepo(observed = MutableStateFlow(before), refreshResult = Result.success(before))
        val vm = vm(repo, connection)
        val seen = statesOf(vm)
        val refresh = repo.holdNextRead()
        vm.refresh()

        refresh.complete(Result.failure(GatewayUnreachableException(GATEWAY_URL)))
        assertEquals(before, assertInstanceOf(InstalledSkillsUiState.Ready::class.java, vm.state.value).skills)
        connection.value = session(reconnecting = true)
        assertEquals(before, assertInstanceOf(InstalledSkillsUiState.Ready::class.java, vm.state.value).skills)
        repo.refreshResult = Result.success(after)
        connection.value = session(generation = 2L)

        assertEquals(after, assertInstanceOf(InstalledSkillsUiState.Ready::class.java, vm.state.value).skills)
        assertNoFailureShown(seen)
    }

    @Test
    fun `rows on screen - refresh fails at the drop, then reconnecting, then the reconnect fails`() = runTest {
        val list = listOf(skill("a/b"))
        val connection = MutableStateFlow<GatewayConnection>(session())
        val repo = FakeInstalledRepo(observed = MutableStateFlow(list), refreshResult = Result.success(list))
        val vm = vm(repo, connection)
        val seen = statesOf(vm)
        val refresh = repo.holdNextRead()
        vm.refresh()

        refresh.complete(Result.failure(GatewayUnreachableException(GATEWAY_URL)))
        connection.value = session(reconnecting = true)
        connection.value = reconnectFailed

        assertInstanceOf(InstalledSkillsUiState.Disconnected::class.java, vm.state.value)
        assertNoFailureShown(seen)
    }

    @Test
    fun `an end-of-socket failure that no state change follows is shown after the grace period`() = runTest {
        val repo = emptyRepo()
        val first = repo.holdNextRead()
        val vm = vm(repo)

        first.complete(Result.failure(GatewayUnreachableException(GATEWAY_URL)))
        advanceTimeBy(SOCKET_END_GRACE_MS - 1)
        assertSame(InstalledSkillsUiState.Loading, vm.state.value)
        advanceTimeBy(2)

        val s = assertInstanceOf(InstalledSkillsUiState.LoadFailed::class.java, vm.state.value)
        assertEquals("Gateway unreachable at $GATEWAY_URL (GatewayUnreachableException)", s.message)
    }

    @Test
    fun `a late answer from the old session after the new session is live is ignored`() = runTest {
        val old = listOf(skill("old/one"))
        val fresh = listOf(skill("new/one"))
        val connection = MutableStateFlow<GatewayConnection>(session())
        val repo = emptyRepo()
        val oldRead = repo.holdNextRead()
        val freshRead = repo.holdNextRead()
        val vm = vm(repo, connection)

        connection.value = session(reconnecting = true)
        connection.value = session(generation = 2L)
        oldRead.complete(Result.success(old))
        assertSame(InstalledSkillsUiState.Loading, vm.state.value)
        freshRead.complete(Result.success(fresh))

        assertEquals(fresh, assertInstanceOf(InstalledSkillsUiState.Ready::class.java, vm.state.value).skills)
    }

    @Test
    fun `two rapid reconnects end on the newest session's list`() = runTest {
        val first = listOf(skill("a/one"))
        val second = listOf(skill("b/two"))
        val third = listOf(skill("c/three"))
        val connection = MutableStateFlow<GatewayConnection>(session())
        val repo = FakeInstalledRepo(observed = MutableStateFlow(first), refreshResult = Result.success(first))
        val vm = vm(repo, connection)
        val secondRead = repo.holdNextRead()
        val thirdRead = repo.holdNextRead()

        connection.value = session(reconnecting = true)
        connection.value = session(generation = 2L)
        connection.value = session(generation = 2L, reconnecting = true)
        connection.value = session(generation = 3L)
        thirdRead.complete(Result.success(third))
        secondRead.complete(Result.success(second))

        assertEquals(third, assertInstanceOf(InstalledSkillsUiState.Ready::class.java, vm.state.value).skills)
        assertEquals(3, repo.refreshCalls)
    }

    @Test
    fun `Refresh is offered only while a session is live`() = runTest {
        val connection = MutableStateFlow<GatewayConnection>(session())
        val vm = vm(emptyRepo(), connection)
        assertTrue(vm.canRefresh.value)

        connection.value = session(reconnecting = true)
        assertEquals(false, vm.canRefresh.value)
        connection.value = reconnectFailed
        assertEquals(false, vm.canRefresh.value)
        connection.value = GatewayConnection.Connecting(GATEWAY_URL)
        assertEquals(false, vm.canRefresh.value)
        connection.value = session(generation = 2L)
        assertTrue(vm.canRefresh.value)
    }

    @Test
    fun `never connected shows Disconnected without a reason and reads nothing`() = runTest {
        val repo = emptyRepo()

        val vm = vm(repo, MutableStateFlow(GatewayConnection.Disconnected))

        assertEquals(InstalledSkillsUiState.Disconnected(reason = null), vm.state.value)
        assertEquals(0, repo.refreshCalls)
    }

    @Test
    fun `refresh while disconnected sends nothing and starts no spinner`() = runTest {
        val repo = emptyRepo()
        val vm = vm(repo, MutableStateFlow(reconnectFailed))

        vm.refresh()

        assertInstanceOf(InstalledSkillsUiState.Disconnected::class.java, vm.state.value)
        assertEquals(0, repo.refreshCalls)
    }

    @Test
    fun `a later session clears Disconnected and loads its list`() = runTest {
        val list = listOf(skill("a/b"))
        val connection = MutableStateFlow<GatewayConnection>(session(reconnecting = true))
        val repo = FakeInstalledRepo(observed = MutableStateFlow(list), refreshResult = Result.success(list))
        val vm = vm(repo, connection)
        connection.value = reconnectFailed
        assertInstanceOf(InstalledSkillsUiState.Disconnected::class.java, vm.state.value)

        connection.value = session(generation = 2L)

        assertEquals(list, assertInstanceOf(InstalledSkillsUiState.Ready::class.java, vm.state.value).skills)
        assertEquals(1, repo.refreshCalls)
    }

    @Test
    fun `a successful silent reconnect re-reads the list once, keeping the rows meanwhile`() = runTest {
        val list = listOf(skill("a/b"))
        val connection = MutableStateFlow<GatewayConnection>(session())
        val repo = FakeInstalledRepo(observed = MutableStateFlow(list), refreshResult = Result.success(list))
        val vm = vm(repo, connection)

        connection.value = session(reconnecting = true)
        connection.value = session(generation = 2L)

        assertEquals(list, assertInstanceOf(InstalledSkillsUiState.Ready::class.java, vm.state.value).skills)
        assertEquals(2, repo.refreshCalls)
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    @Test
    fun `requestUninstall then cancelUninstall toggles pendingUninstallSlug`() = runTest {
        val repo = FakeInstalledRepo(
            observed = MutableStateFlow(listOf(skill("a/b"))),
            refreshResult = Result.success(listOf(skill("a/b"))),
        )
        val vm = vm(repo)

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
        val vm = vm(repo)

        vm.requestUninstall("a/b")
        vm.confirmUninstall()

        val s = vm.state.value as InstalledSkillsUiState.Ready
        assertNull(s.pendingUninstallSlug)
        assertNull(s.uninstallingSlug)
        assertEquals("a/b", s.uninstallSuccessSlug)
        assertEquals(listOf("a/b" to false), repo.enabledCalls)
        assertTrue(repo.uninstallCalls.isEmpty())
    }

    @Test
    fun `confirmUninstall failure sets errorMessage`() = runTest {
        val repo = FakeInstalledRepo(
            observed = MutableStateFlow(listOf(skill("a/b"))),
            refreshResult = Result.success(listOf(skill("a/b"))),
            uninstallResult = Result.failure(RuntimeException("boom")),
        )
        val vm = vm(repo)

        vm.requestUninstall("a/b")
        vm.confirmUninstall()

        val s = vm.state.value as InstalledSkillsUiState.Ready
        assertNull(s.uninstallingSlug)
        assertNull(s.uninstallSuccessSlug)
        assertEquals("boom (RuntimeException)", s.errorMessage)
    }

    @Test
    fun `confirmUninstall is no-op when no pending slug`() = runTest {
        val repo = FakeInstalledRepo(
            observed = MutableStateFlow(emptyList()),
            refreshResult = Result.success(emptyList()),
        )
        val vm = vm(repo)

        vm.confirmUninstall()
        assertTrue(repo.uninstallCalls.isEmpty())
        assertTrue(repo.enabledCalls.isEmpty())
    }

    @Test
    fun `clearUninstallSuccess and clearError clear their respective fields`() = runTest {
        val repo = FakeInstalledRepo(
            observed = MutableStateFlow(listOf(skill("a/b"))),
            refreshResult = Result.success(listOf(skill("a/b"))),
        )
        val vm = vm(repo)

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
        val vm2 = vm(failRepo)
        vm2.requestUninstall("x/y")
        vm2.confirmUninstall()
        assertEquals("nope (RuntimeException)", (vm2.state.value as InstalledSkillsUiState.Ready).errorMessage)

        vm2.clearError()
        assertNull((vm2.state.value as InstalledSkillsUiState.Ready).errorMessage)
    }

    @Test
    fun `a disable cut off by the drop reports nothing - the reconnect that follows decides`() = runTest {
        val list = listOf(skill("a/b"))
        val connection = MutableStateFlow<GatewayConnection>(session())
        val repo = FakeInstalledRepo(observed = MutableStateFlow(list), refreshResult = Result.success(list))
        val vm = vm(repo, connection)
        val seen = statesOf(vm)
        val disable = repo.holdNextDisable()
        vm.requestUninstall("a/b")
        vm.confirmUninstall()

        disable.complete(Result.failure(GatewayUnreachableException(GATEWAY_URL)))
        assertEquals("a/b", (vm.state.value as InstalledSkillsUiState.Ready).uninstallingSlug)
        connection.value = session(reconnecting = true)
        val during = assertInstanceOf(InstalledSkillsUiState.Ready::class.java, vm.state.value)
        assertNull(during.uninstallingSlug)
        assertEquals(list, during.skills)
        connection.value = reconnectFailed

        assertInstanceOf(InstalledSkillsUiState.Disconnected::class.java, vm.state.value)
        assertNoFailureShown(seen)
    }

    @Test
    fun `a disable whose socket ended with no state change after it is reported after the grace period`() =
        runTest {
            val list = listOf(skill("a/b"))
            val repo = FakeInstalledRepo(observed = MutableStateFlow(list), refreshResult = Result.success(list))
            val vm = vm(repo)
            val disable = repo.holdNextDisable()
            vm.requestUninstall("a/b")
            vm.confirmUninstall()

            disable.complete(Result.failure(GatewayUnreachableException(GATEWAY_URL)))
            assertNull((vm.state.value as InstalledSkillsUiState.Ready).errorMessage)
            advanceTimeBy(SOCKET_END_GRACE_MS + 1)

            val s = vm.state.value as InstalledSkillsUiState.Ready
            assertNull(s.uninstallingSlug)
            assertEquals("Gateway unreachable at $GATEWAY_URL (GatewayUnreachableException)", s.errorMessage)
        }

    @Test
    fun `a disable tapped during the reconnect says it could not be sent`() = runTest {
        val list = listOf(skill("a/b"))
        val connection = MutableStateFlow<GatewayConnection>(session())
        val repo = FakeInstalledRepo(
            observed = MutableStateFlow(list),
            refreshResult = Result.success(list),
            uninstallResult = Result.failure(GatewayNotConnectedException(reconnectingTo = GATEWAY_URL)),
        )
        val vm = vm(repo, connection)
        connection.value = session(reconnecting = true)

        vm.requestUninstall("a/b")
        vm.confirmUninstall()

        val s = vm.state.value as InstalledSkillsUiState.Ready
        assertNull(s.uninstallingSlug)
        assertEquals("Reconnecting to $GATEWAY_URL… (GatewayNotConnectedException)", s.errorMessage)
    }

    @Test
    fun `a disable ending with the session leaves no row spinner for the next session`() = runTest {
        val list = listOf(skill("a/b"), skill("c/d"))
        val connection = MutableStateFlow<GatewayConnection>(session())
        val repo = FakeInstalledRepo(observed = MutableStateFlow(list), refreshResult = Result.success(list))
        val vm = vm(repo, connection)
        val disable = repo.holdNextDisable()
        vm.requestUninstall("a/b")
        vm.confirmUninstall()

        connection.value = reconnectFailed
        connection.value = session(generation = 2L)
        assertNull((vm.state.value as InstalledSkillsUiState.Ready).uninstallingSlug)
        vm.requestUninstall("c/d")
        val second = repo.holdNextDisable()
        vm.confirmUninstall()
        disable.complete(Result.success(Unit)) // the first session's disable answers late

        assertEquals("c/d", (vm.state.value as InstalledSkillsUiState.Ready).uninstallingSlug)
        second.complete(Result.success(Unit))
        assertNull((vm.state.value as InstalledSkillsUiState.Ready).uninstallingSlug)
    }

    @Test
    fun `refresh success clears isRefreshing`() = runTest {
        val list = listOf(skill("a/b"))
        val repo = FakeInstalledRepo(
            observed = MutableStateFlow(list),
            refreshResult = Result.success(list),
        )
        val vm = vm(repo)
        val initialCalls = repo.refreshCalls

        vm.refresh()

        val s = vm.state.value as InstalledSkillsUiState.Ready
        assertEquals(false, s.isRefreshing)
        assertEquals(initialCalls + 1, repo.refreshCalls)
    }

    private companion object {
        const val GATEWAY_URL = "http://192.168.1.20:18789"
    }
}

// ── Fakes ─────────────────────────────────────────────────────────────────────

private class FakeSkillsConnectionManager(
    flow: MutableStateFlow<GatewayConnection>,
) : GatewayConnectionManager {
    override val connectionState: StateFlow<GatewayConnection> = flow
    override suspend fun probeAuth(url: String) = AuthRequirement.None
    override suspend fun connect(url: String, token: String?) = Unit
    override suspend fun disconnect() = Unit
}

private class FakeInstalledRepo(
    private val observed: MutableStateFlow<List<InstalledSkill>>,
    var refreshResult: Result<List<InstalledSkill>>,
    private val uninstallResult: Result<Unit> = Result.success(Unit),
) : InstalledSkillRepository {
    var refreshCalls = 0
    val uninstallCalls = mutableListOf<String>()
    val enabledCalls = mutableListOf<Pair<String, Boolean>>()
    private val heldReads = ArrayDeque<CompletableDeferred<Result<List<InstalledSkill>>>>()
    private val heldDisables = ArrayDeque<CompletableDeferred<Result<Unit>>>()

    /** The next unheld `refreshFromGateway()` answers only when the returned deferred completes, with its value. */
    fun holdNextRead() = CompletableDeferred<Result<List<InstalledSkill>>>().also { heldReads.addLast(it) }

    /** The next unheld `setSkillEnabled()` answers only when the returned deferred completes, with its value. */
    fun holdNextDisable() = CompletableDeferred<Result<Unit>>().also { heldDisables.addLast(it) }

    override suspend fun setSkillEnabled(skillKey: String, enabled: Boolean): Result<Unit> {
        enabledCalls += skillKey to enabled
        return heldDisables.removeFirstOrNull()?.await() ?: uninstallResult
    }

    override fun observeInstalled(): Flow<List<InstalledSkill>> = observed
    override suspend fun refreshFromGateway(): Result<List<InstalledSkill>> {
        refreshCalls++
        return heldReads.removeFirstOrNull()?.await() ?: refreshResult
    }
    override fun install(slug: String, version: String?): Flow<SkillInstallProgress> = emptyFlow()
    override suspend fun uninstall(slug: String): Result<Unit> {
        uninstallCalls += slug
        return uninstallResult
    }
}
