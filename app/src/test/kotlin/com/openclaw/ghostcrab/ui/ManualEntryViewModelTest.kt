package com.openclaw.ghostcrab.ui

import app.cash.turbine.test
import com.openclaw.ghostcrab.domain.exception.DeviceCredentialStorageException
import com.openclaw.ghostcrab.domain.exception.GatewayPairingRequiredException
import com.openclaw.ghostcrab.domain.exception.ProfileNeedsReauthException
import com.openclaw.ghostcrab.domain.model.ConnectionWarning
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import com.openclaw.ghostcrab.domain.repository.ConnectionProfileRepository
import com.openclaw.ghostcrab.domain.repository.GatewayConnectionManager
import com.openclaw.ghostcrab.domain.repository.OnboardingRepository
import com.openclaw.ghostcrab.domain.util.DEFAULT_GATEWAY_PORT
import com.openclaw.ghostcrab.domain.util.GatewayUrl
import com.openclaw.ghostcrab.domain.util.PairBundle
import com.openclaw.ghostcrab.ui.connection.ManualEntryEvent
import com.openclaw.ghostcrab.ui.connection.ManualEntryFormState
import com.openclaw.ghostcrab.ui.connection.ManualEntryUiState
import com.openclaw.ghostcrab.ui.connection.ManualEntryViewModel
import com.openclaw.ghostcrab.ui.connection.PAIRING_RETRY_INTERVAL_MS
import com.openclaw.ghostcrab.ui.connection.PAIRING_TIMEOUT_MS
import com.openclaw.ghostcrab.ui.connection.PairingHandoff
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
import java.security.GeneralSecurityException

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
        vm = ManualEntryViewModel(connectionManager, profileRepository, onboardingRepository, PairingHandoff())
    }

    @AfterEach
    fun tearDown() { Dispatchers.resetMain() }

    // ── Defaults ──────────────────────────────────────────────────────────────

    @Test
    fun `default form has http scheme and default port 18789`() {
        val form = vm.form.value
        assertFalse(form.useHttps)
        assertEquals("http", form.scheme)
        assertEquals(DEFAULT_GATEWAY_PORT.toString(), form.port)
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
        assertEquals(DEFAULT_GATEWAY_PORT.toString(), vm.form.value.port)
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
    fun `assembledUrl canonicalises a zero-padded port and brackets IPv6`() {
        vm.onHostChange("::1")
        vm.onPortChange("08080")
        assertEquals("http://[::1]:8080", vm.form.value.assembledUrl)
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
        assertEquals("refused (RuntimeException)", (vm.uiState.value as ManualEntryUiState.Error).message)
    }

    @Test
    fun `percent in a non-IPv6 host is rejected`() {
        vm.onHostChange("gw%foo")
        vm.connect()
        assertEquals(null, vm.form.value.portError)
        assertTrue(vm.form.value.hostError != null)
    }

    @Test
    fun `IPv6 host with zone id still validates`() {
        vm.onHostChange("fe80::1%wlan0")
        vm.connect()
        assertNull(vm.form.value.hostError)
    }

    @Test
    fun `host with at-sign question-mark hash or backslash is rejected`() {
        for (bad in listOf("user@192.168.1.5", "gw?x=1", "gw#frag", "gw" + 92.toChar() + "evil")) {
            vm.onHostChange(bad)
            vm.connect()
            assertTrue(vm.form.value.hostError != null, "expected hostError for '$bad'")
            assertEquals(ManualEntryUiState.Idle, vm.uiState.value)
        }
    }

    @Test
    fun `form state toString redacts the token`() {
        val state = ManualEntryFormState(host = "gw", token = "hunter2-token")
        val rendered = state.toString()
        assertTrue(!rendered.contains("hunter2-token"), rendered)
        assertTrue(rendered.contains("token=[REDACTED]"), rendered)
        assertTrue(ManualEntryFormState().toString().contains("token=,"))
    }

    @Test
    fun `profile save failure after a successful connect sets Error instead of crashing`() = runTest {
        coEvery { connectionManager.connect(any(), any()) } returns Unit
        coEvery { profileRepository.saveProfile(any(), any()) } throws ProfileNeedsReauthException("p1")
        vm.onHostChange("192.168.0.23")

        vm.connect()

        val state = vm.uiState.value as ManualEntryUiState.Error
        assertTrue(state.message.contains("ProfileNeedsReauthException"))
    }

    // ── Pairing approval (ADR-0007) ───────────────────────────────────────────

    private val pending =
        GatewayPairingRequiredException("ws://192.168.1.50:18789/ws", "req-1", "not-paired", listOf("operator.read"))

    private fun fillForm() {
        vm.onHostChange("192.168.1.50")
        vm.onTokenChange("shared-token")
    }

    @Test fun `pairing required shows the approval prompt and connects once approved`() = runTest {
        var calls = 0
        coEvery { connectionManager.connect(any(), any()) } coAnswers { if (calls++ == 0) throw pending }
        fillForm()
        vm.events.test {
            vm.connect()
            val waiting = vm.uiState.value as ManualEntryUiState.AwaitingApproval
            assertEquals("openclaw devices approve req-1", waiting.prompt.approveCommand)
            advanceTimeBy(PAIRING_RETRY_INTERVAL_MS + 1)
            assertEquals(ManualEntryEvent.NavigateToDashboard, awaitItem())
        }
        assertEquals(ManualEntryUiState.Idle, vm.uiState.value)
        coVerify(exactly = 2) { connectionManager.connect("http://192.168.1.50:18789", "shared-token") }
    }

    @Test fun `the profile is saved only once the device is approved`() = runTest {
        coEvery { connectionManager.connect(any(), any()) } throws pending
        fillForm()
        vm.connect()
        advanceTimeBy(PAIRING_RETRY_INTERVAL_MS * 3)
        coVerify(exactly = 0) { profileRepository.saveProfile(any(), any()) }
        coVerify(exactly = 0) { onboardingRepository.markCompleted() }
        vm.cancelPairing()
    }

    @Test fun `the approval prompt carries the warnings of the failed connect`() = runTest {
        every { connectionManager.connectionState } returns MutableStateFlow(
            GatewayConnection.Error(pending.url, pending, listOf(ConnectionWarning.DeviceIdentityRegenerated)),
        )
        coEvery { connectionManager.connect(any(), any()) } throws pending
        fillForm()
        vm.connect()
        val waiting = vm.uiState.value as ManualEntryUiState.AwaitingApproval
        assertEquals(listOf(ConnectionWarning.DeviceIdentityRegenerated), waiting.prompt.warnings)
        vm.cancelPairing()
    }

    @Test fun `cancelPairing stops retrying and returns to Idle`() = runTest {
        coEvery { connectionManager.connect(any(), any()) } throws pending
        fillForm()
        vm.connect()
        vm.cancelPairing()
        advanceTimeBy(60_000)
        assertEquals(ManualEntryUiState.Idle, vm.uiState.value)
        coVerify(exactly = 1) { connectionManager.connect(any(), any()) }
    }

    @Test fun `checkPairingNow retries without waiting`() = runTest {
        var calls = 0
        coEvery { connectionManager.connect(any(), any()) } coAnswers { if (calls++ == 0) throw pending }
        fillForm()
        vm.connect()
        vm.checkPairingNow()
        runCurrent()
        coVerify(exactly = 2) { connectionManager.connect(any(), any()) }
        assertEquals(ManualEntryUiState.Idle, vm.uiState.value)
    }

    @Test fun `connect while waiting for approval is ignored`() = runTest {
        coEvery { connectionManager.connect(any(), any()) } throws pending
        fillForm()
        vm.connect()
        vm.connect()
        coVerify(exactly = 1) { connectionManager.connect(any(), any()) }
        vm.cancelPairing()
    }

    @Test fun `a credential-storage failure during the wait is an error, never retried`() = runTest {
        var calls = 0
        val storage = DeviceCredentialStorageException("open the device identity", GeneralSecurityException("ks"))
        coEvery { connectionManager.connect(any(), any()) } coAnswers {
            if (calls++ == 0) throw pending else throw storage
        }
        fillForm()
        vm.connect()
        advanceTimeBy(PAIRING_RETRY_INTERVAL_MS * 4)
        assertTrue((vm.uiState.value as ManualEntryUiState.Error).message.contains("DeviceCredentialStorageException"))
        coVerify(exactly = 2) { connectionManager.connect(any(), any()) }
    }

    @Test fun `an expired approval window is an explicit error`() = runTest {
        coEvery { connectionManager.connect(any(), any()) } throws pending
        fillForm()
        vm.connect()
        advanceTimeBy(PAIRING_TIMEOUT_MS + 1)
        assertTrue((vm.uiState.value as ManualEntryUiState.Error).message.contains("PAIRING_REQUIRED"))
    }

    @Test
    fun `IPv6 prefill keeps the bare host and assembles a bracketed URL`() {
        vm.setPrefillUrl("http://[fe80::1]:18789")
        assertEquals("fe80::1", vm.form.value.host)
        assertEquals("http://[fe80::1]:18789", vm.form.value.assembledUrl)
    }

    @Test
    fun `IPv6 literal host passes validation and connects`() = runTest {
        coEvery { connectionManager.connect(any(), any()) } returns Unit
        vm.setPrefillUrl("http://[fe80::1]:18789")

        vm.connect()

        assertNull(vm.form.value.hostError)
        coVerify { connectionManager.connect("http://[fe80::1]:18789", null) }
    }

    @Test
    fun `https prefill without port defaults to 443`() {
        vm.setPrefillUrl("https://abc-xyz.trycloudflare.com")
        assertEquals("443", vm.form.value.port)
    }

    @Test
    fun `consumePendingPairing prefills host, port and token without connecting`() {
        val handoff = PairingHandoff().apply {
            offer(PairBundle(GatewayUrl("https", "gw.example", 8443), token = "tkn", name = "Den"))
        }
        val pairedVm = ManualEntryViewModel(connectionManager, profileRepository, onboardingRepository, handoff)

        assertTrue(pairedVm.consumePendingPairing())

        val form = pairedVm.form.value
        assertTrue(form.useHttps)
        assertEquals("gw.example", form.host)
        assertEquals("8443", form.port)
        assertEquals("tkn", form.token)
        assertFalse(pairedVm.consumePendingPairing())
        coVerify(exactly = 0) { connectionManager.connect(any(), any()) }
    }

    @Test
    fun `paired name is used for the saved profile`() = runTest {
        val handoff = PairingHandoff().apply {
            offer(PairBundle(GatewayUrl("http", "192.168.0.23", 18789), token = "tkn", name = "Den"))
        }
        val pairedVm = ManualEntryViewModel(connectionManager, profileRepository, onboardingRepository, handoff)
        coEvery { connectionManager.connect(any(), any()) } returns Unit
        pairedVm.consumePendingPairing()

        pairedVm.connect()

        coVerify { profileRepository.saveProfile(match { it.displayName == "Den" }, "tkn") }
    }

    @Test
    fun `an offer made while the screen is open bumps pairingOffers`() {
        val handoff = PairingHandoff()
        val pairedVm = ManualEntryViewModel(connectionManager, profileRepository, onboardingRepository, handoff)
        val before = pairedVm.pairingOffers.value

        handoff.offer(PairBundle(GatewayUrl("http", "192.168.0.23", 18789), token = null, name = null))

        assertEquals(before + 1, pairedVm.pairingOffers.value)
        assertTrue(pairedVm.consumePendingPairing())
    }

    private fun pairedVm(token: String? = "tkn"): ManualEntryViewModel {
        val handoff = PairingHandoff().apply {
            offer(PairBundle(GatewayUrl("http", "gw-a.example", 18789), token = token, name = "Den"))
        }
        return ManualEntryViewModel(connectionManager, profileRepository, onboardingRepository, handoff)
            .also { it.consumePendingPairing() }
    }

    @Test
    fun `editing the host after a pairing link drops the paired token and name`() = runTest {
        val vm = pairedVm()
        vm.onHostChange("gw-b.example")
        assertEquals("", vm.form.value.token)
        vm.connect()
        coVerify { connectionManager.connect("http://gw-b.example:18789", null) }
        coVerify(exactly = 0) { profileRepository.saveProfile(match { it.displayName == "Den" }, any()) }
    }

    @Test
    fun `editing port or https after a pairing link drops the paired token`() {
        val a = pairedVm().also { it.onPortChange("9999") }
        assertEquals("", a.form.value.token)
        val b = pairedVm().also { it.onHttpsToggle(true) }
        assertEquals("", b.form.value.token)
    }

    @Test
    fun `a token typed over the paired one survives an address edit`() {
        val vm = pairedVm()
        vm.onTokenChange("mine")
        vm.onHostChange("gw-b.example")
        assertEquals("mine", vm.form.value.token)
    }

    @Test
    fun `re-entering the same host keeps the paired token`() {
        val vm = pairedVm()
        vm.onHostChange("gw-a.example")
        assertEquals("tkn", vm.form.value.token)
    }

    @Test
    fun `re-applying the same prefill url keeps a typed token, a different one clears it`() {
        vm.setPrefillUrl("http://gw-a.example:18789")
        vm.onTokenChange("T")
        vm.setPrefillUrl("http://gw-a.example:18789")
        assertEquals("T", vm.form.value.token)
        vm.setPrefillUrl("http://gw-b.example:18789")
        assertEquals("", vm.form.value.token)
    }

    @Test
    fun `setPrefillUrl clears a token and name left over from an earlier pairing`() {
        val vm = pairedVm()
        vm.setPrefillUrl("http://gw-b.example:18789")
        assertEquals("", vm.form.value.token)
        assertEquals("gw-b.example", vm.form.value.host)
    }
}
