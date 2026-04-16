package com.openclaw.ghostcrab.ui

import android.util.Patterns
import app.cash.turbine.test
import com.openclaw.ghostcrab.domain.model.AuthRequirement
import com.openclaw.ghostcrab.domain.model.ConnectionProfile
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import com.openclaw.ghostcrab.domain.repository.ConnectionProfileRepository
import com.openclaw.ghostcrab.domain.repository.GatewayConnectionManager
import com.openclaw.ghostcrab.ui.connection.ManualEntryEvent
import com.openclaw.ghostcrab.ui.connection.ManualEntryUiState
import com.openclaw.ghostcrab.ui.connection.ManualEntryViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.util.regex.Matcher
import java.util.regex.Pattern

@OptIn(ExperimentalCoroutinesApi::class)
class ManualEntryViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var connectionManager: GatewayConnectionManager
    private lateinit var profileRepository: ConnectionProfileRepository
    private lateinit var vm: ManualEntryViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        connectionManager = mockk(relaxed = true)
        profileRepository = mockk(relaxed = true)
        every { connectionManager.connectionState } returns
            MutableStateFlow(GatewayConnection.Disconnected)
        every { profileRepository.getProfiles() } returns flowOf(emptyList())
        vm = ManualEntryViewModel(connectionManager, profileRepository)
    }

    @AfterEach
    fun tearDown() { Dispatchers.resetMain() }

    // ── Form state mutations ──────────────────────────────────────────────────

    @Test
    fun `onUrlChange updates url and clears existing urlError`() {
        vm.connect() // blank url → sets urlError
        assertTrue(vm.form.value.urlError != null)
        vm.onUrlChange("http://new")
        assertEquals("http://new", vm.form.value.url)
        assertNull(vm.form.value.urlError)
    }

    @Test
    fun `onTokenChange updates token field`() {
        vm.onTokenChange("super-secret")
        assertEquals("super-secret", vm.form.value.token)
    }

    @Test
    fun `toggleTokenVisibility flips tokenVisible`() {
        assertFalse(vm.form.value.tokenVisible)
        vm.toggleTokenVisibility()
        assertTrue(vm.form.value.tokenVisible)
        vm.toggleTokenVisibility()
        assertFalse(vm.form.value.tokenVisible)
    }

    @Test
    fun `setPrefillUrl populates url field`() {
        vm.setPrefillUrl("http://192.168.1.100:18789")
        assertEquals("http://192.168.1.100:18789", vm.form.value.url)
    }

    // ── Validation ────────────────────────────────────────────────────────────

    @Test
    fun `connect with blank url sets urlError and stays Idle`() {
        vm.connect()
        assertEquals("URL is required", vm.form.value.urlError)
        assertInstanceOf(ManualEntryUiState.Idle::class.java, vm.uiState.value)
    }

    @Test
    fun `connect without http or https prefix sets urlError`() {
        vm.onUrlChange("192.168.1.50:18789")
        vm.connect()
        assertEquals("URL must start with http:// or https://", vm.form.value.urlError)
    }

    // ── Connect paths (require Patterns mock) ─────────────────────────────────

    private fun mockPatterns() {
        mockkStatic(Patterns::class)
        val pattern = mockk<Pattern>()
        val matcher = mockk<Matcher>()
        every { Patterns.WEB_URL } returns pattern
        every { pattern.matcher(any()) } returns matcher
        every { matcher.matches() } returns true
    }

    @Disabled("Patterns.WEB_URL static field cannot be intercepted by MockK in unit tests — covered by androidTest")
    @Test
    fun `connect success emits NavigateToDashboard event`() = runTest {
        mockPatterns()
        coEvery { connectionManager.connect(any(), any()) } returns Unit
        vm.onUrlChange("http://192.168.1.50:18789")

        vm.events.test {
            vm.connect()
            assertEquals(ManualEntryEvent.NavigateToDashboard, awaitItem())
        }
        unmockkStatic(Patterns::class)
    }

    @Disabled("Patterns.WEB_URL static field cannot be intercepted by MockK in unit tests — covered by androidTest")
    @Test
    fun `connect failure sets Error uiState with exception message`() = runTest {
        mockPatterns()
        coEvery { connectionManager.connect(any(), any()) } throws RuntimeException("refused")
        vm.onUrlChange("http://192.168.1.50:18789")
        vm.connect()

        assertInstanceOf(ManualEntryUiState.Error::class.java, vm.uiState.value)
        assertEquals("refused", (vm.uiState.value as ManualEntryUiState.Error).message)
        unmockkStatic(Patterns::class)
    }
}
