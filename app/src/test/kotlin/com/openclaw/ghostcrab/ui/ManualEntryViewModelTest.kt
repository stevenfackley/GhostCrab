package com.openclaw.ghostcrab.ui

import app.cash.turbine.test
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import com.openclaw.ghostcrab.domain.repository.ConnectionProfileRepository
import com.openclaw.ghostcrab.domain.repository.GatewayConnectionManager
import com.openclaw.ghostcrab.domain.repository.OnboardingRepository
import com.openclaw.ghostcrab.ui.connection.DEFAULT_GATEWAY_PORT
import com.openclaw.ghostcrab.ui.connection.ManualEntryEvent
import com.openclaw.ghostcrab.ui.connection.ManualEntryUiState
import com.openclaw.ghostcrab.ui.connection.ManualEntryViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ManualEntryViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var connectionManager: GatewayConnectionManager
    private lateinit var profileRepository: ConnectionProfileRepository
    private lateinit var onboardingRepository: OnboardingRepository
    private lateinit var vm: ManualEntryViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        connectionManager = mockk(relaxed = true)
        profileRepository = mockk(relaxed = true)
        onboardingRepository = mockk(relaxed = true)
        every { connectionManager.connectionState } returns
            MutableStateFlow(GatewayConnection.Disconnected)
        every { profileRepository.getProfiles() } returns flowOf(emptyList())
        vm = ManualEntryViewModel(connectionManager, profileRepository, onboardingRepository)
    }

    @AfterEach
    fun tearDown() { Dispatchers.resetMain() }

    // ── Defaults ──────────────────────────────────────────────────────────────

    @Test
    fun `default form has http scheme and default port 18789`() {
        val form = vm.form.value
        assertFalse(form.useHttps)
        assertEquals("http", form.scheme)
        assertEquals(DEFAULT_GATEWAY_PORT, form.port)
        assertEquals("", form.host)
    }

    // ── Form state mutations ──────────────────────────────────────────────────

    @Test
    fun `onHostChange updates host and clears existing hostError`() {
        vm.connect() // blank host → sets hostError
        assertTrue(vm.form.value.hostError != null)
        vm.onHostChange("192.168.0.23")
        assertEquals("192.168.0.23", vm.form.value.host)
        assertNull(vm.form.value.hostError)
    }

    @Test
    fun `onPortChange strips non-digits and caps at 5 chars`() {
        vm.onPortChange("1a2b3c4d5e6f")
        assertEquals("12345", vm.form.value.port)
    }

    @Test
    fun `onHttpsToggle flips scheme`() {
        assertFalse(vm.form.value.useHttps)
        vm.onHttpsToggle(true)
        assertTrue(vm.form.value.useHttps)
        assertEquals("https", vm.form.value.scheme)
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
    }

    @Test
    fun `setPrefillUrl splits scheme host and port`() {
        vm.setPrefillUrl("https://10.0.0.5:9000")
        val form = vm.form.value
        assertTrue(form.useHttps)
        assertEquals("10.0.0.5", form.host)
        assertEquals("9000", form.port)
    }

    @Test
    fun `setPrefillUrl with no port falls back to default`() {
        vm.setPrefillUrl("http://host.local")
        assertEquals(DEFAULT_GATEWAY_PORT, vm.form.value.port)
    }

    // ── Validation ────────────────────────────────────────────────────────────

    @Test
    fun `connect with blank host sets hostError and stays Idle`() {
        vm.connect()
        assertEquals("Host is required", vm.form.value.hostError)
        assertInstanceOf(ManualEntryUiState.Idle::class.java, vm.uiState.value)
    }

    @Test
    fun `host with scheme prefix is rejected`() {
        vm.onHostChange("http://192.168.0.23")
        vm.connect()
        assertEquals("Remove scheme — use the HTTPS toggle instead", vm.form.value.hostError)
    }

    @Test
    fun `host with colon is rejected — port belongs in Port field`() {
        vm.onHostChange("192.168.0.23:18789")
        vm.connect()
        assertEquals("Port goes in the Port field, not the Host field", vm.form.value.hostError)
    }

    @Test
    fun `host with slash is rejected`() {
        vm.onHostChange("host/path")
        vm.connect()
        assertEquals("Host must not contain '/'", vm.form.value.hostError)
    }

    @Test
    fun `port out of range is rejected`() {
        vm.onHostChange("192.168.0.23")
        vm.onPortChange("70000")
        vm.connect()
        assertEquals("Port must be between 1 and 65535", vm.form.value.portError)
    }

    @Test
    fun `empty port is rejected`() {
        vm.onHostChange("192.168.0.23")
        vm.onPortChange("")
        vm.connect()
        assertEquals("Port is required", vm.form.value.portError)
    }

    // ── Connect flow ──────────────────────────────────────────────────────────

    @Test
    fun `assembledUrl concatenates scheme host and port`() {
        vm.onHttpsToggle(true)
        vm.onHostChange("10.0.0.5")
        vm.onPortChange("9000")
        assertEquals("https://10.0.0.5:9000", vm.form.value.assembledUrl)
    }

    @Test
    fun `connect calls connectionManager with assembled URL`() = runTest {
        coEvery { connectionManager.connect(any(), any()) } returns Unit
        vm.onHostChange("192.168.0.23")
        // port defaults to 18789

        vm.events.test {
            vm.connect()
            assertEquals(ManualEntryEvent.NavigateToDashboard, awaitItem())
        }
        coVerify { connectionManager.connect("http://192.168.0.23:18789", null) }
    }

    @Test
    fun `connect failure sets Error uiState with exception message`() = runTest {
        coEvery { connectionManager.connect(any(), any()) } throws RuntimeException("refused")
        vm.onHostChange("192.168.0.23")
        vm.connect()

        assertInstanceOf(ManualEntryUiState.Error::class.java, vm.uiState.value)
        assertEquals("refused", (vm.uiState.value as ManualEntryUiState.Error).message)
    }
}
