package com.openclaw.ghostcrab.ui

import app.cash.turbine.test
import com.openclaw.ghostcrab.ui.connection.PairingHandoff
import com.openclaw.ghostcrab.ui.connection.QrScanEvent
import com.openclaw.ghostcrab.ui.connection.QrScanUiState
import com.openclaw.ghostcrab.ui.connection.QrScanViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
class QrScanViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeEach fun setUp() { Dispatchers.setMain(testDispatcher) }
    @AfterEach fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `onPermissionGranted sets Scanning state`() {
        val vm = QrScanViewModel(PairingHandoff())
        vm.onPermissionGranted()
        assertInstanceOf(QrScanUiState.Scanning::class.java, vm.uiState.value)
    }

    @Test
    fun `onPermissionDenied permanent sets PermissionDenied state`() {
        val vm = QrScanViewModel(PairingHandoff())
        vm.onPermissionDenied(isPermanent = true)
        assertInstanceOf(QrScanUiState.PermissionDenied::class.java, vm.uiState.value)
    }

    @Test
    fun `onPermissionDenied not permanent sets PermissionRequired state`() {
        val vm = QrScanViewModel(PairingHandoff())
        vm.onPermissionDenied(isPermanent = false)
        assertInstanceOf(QrScanUiState.PermissionRequired::class.java, vm.uiState.value)
    }

    @Test
    fun `onQrDecoded valid https url emits NavigateToManualEntry with url`() = runTest {
        val vm = QrScanViewModel(PairingHandoff())
        vm.events.test {
            vm.onQrDecoded("https://abc-xyz.trycloudflare.com")
            val event = awaitItem() as QrScanEvent.NavigateToManualEntry
            assertEquals("https://abc-xyz.trycloudflare.com:443", event.url)
        }
    }

    @Test
    fun `onQrDecoded emits only the normalized base url, dropping path query and fragment`() = runTest {
        val vm = QrScanViewModel(PairingHandoff())
        vm.events.test {
            vm.onQrDecoded("http://192.168.1.50:18789/pair?token=abc#frag")
            assertEquals("http://192.168.1.50:18789", (awaitItem() as QrScanEvent.NavigateToManualEntry).url)
        }
    }

    @Test
    fun `onQrDecoded valid http url with port emits NavigateToManualEntry`() = runTest {
        val vm = QrScanViewModel(PairingHandoff())
        vm.events.test {
            vm.onQrDecoded("http://192.168.1.50:18789")
            assertEquals("http://192.168.1.50:18789", (awaitItem() as QrScanEvent.NavigateToManualEntry).url)
        }
    }

    @Test
    fun `onQrDecoded blank string sets InvalidQr state`() {
        val vm = QrScanViewModel(PairingHandoff())
        vm.onQrDecoded("   ")
        assertInstanceOf(QrScanUiState.InvalidQr::class.java, vm.uiState.value)
    }

    @Test
    fun `onQrDecoded non-url text sets InvalidQr state`() {
        val vm = QrScanViewModel(PairingHandoff())
        vm.onQrDecoded("just some random text")
        assertInstanceOf(QrScanUiState.InvalidQr::class.java, vm.uiState.value)
    }

    @Test
    fun `onScanAgain resets to Scanning after InvalidQr`() {
        val vm = QrScanViewModel(PairingHandoff())
        vm.onQrDecoded("not a url")
        assertInstanceOf(QrScanUiState.InvalidQr::class.java, vm.uiState.value)
        vm.onScanAgain()
        assertInstanceOf(QrScanUiState.Scanning::class.java, vm.uiState.value)
    }

    @Test
    fun `second decode after a valid one emits no second navigation`() = runTest {
        val vm = QrScanViewModel(PairingHandoff())
        vm.onPermissionGranted()

        vm.events.test {
            vm.onQrDecoded("http://192.168.1.50:18789")
            vm.onQrDecoded("http://192.168.1.50:18789")
            awaitItem()
            expectNoEvents()
        }
        assertEquals(QrScanUiState.Decoded, vm.uiState.value)
    }

    @Test
    fun `pair code hands the bundle off and navigates without the token in the event`() = runTest {
        val handoff = PairingHandoff()
        val vm = QrScanViewModel(handoff)
        val code = "ghostcrab://pair?p=" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"v":1,"url":"http://192.168.0.23:18789","token":"tkn"}""".toByteArray())

        vm.events.test {
            vm.onQrDecoded(code)
            assertEquals(QrScanEvent.NavigateToPairedEntry, awaitItem())
        }
        assertEquals("tkn", handoff.take()?.token)
    }

    @Test
    fun `onQrDecoded openclaw setup code explains instead of pairing`() = runTest {
        val handoff = PairingHandoff()
        val vm = QrScanViewModel(handoff)
        val code = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"url":"ws://192.168.1.50:18789","bootstrapToken":"bt-test-0001"}""".toByteArray())
        vm.onQrDecoded(code)
        val message = (vm.uiState.value as QrScanUiState.InvalidQr).message
        assertTrue(message.contains("http://192.168.1.50:18789"))
        assertTrue(message.contains("openclaw devices approve"))
        assertNull(handoff.take())
    }
}
