package com.openclaw.ghostcrab.ui

import app.cash.turbine.test
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QrScanViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeEach fun setUp() { Dispatchers.setMain(testDispatcher) }
    @AfterEach fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `onPermissionGranted sets Scanning state`() {
        val vm = QrScanViewModel()
        vm.onPermissionGranted()
        assertInstanceOf(QrScanUiState.Scanning::class.java, vm.uiState.value)
    }

    @Test
    fun `onPermissionDenied permanent sets PermissionDenied state`() {
        val vm = QrScanViewModel()
        vm.onPermissionDenied(isPermanent = true)
        assertInstanceOf(QrScanUiState.PermissionDenied::class.java, vm.uiState.value)
    }

    @Test
    fun `onPermissionDenied not permanent sets PermissionRequired state`() {
        val vm = QrScanViewModel()
        vm.onPermissionDenied(isPermanent = false)
        assertInstanceOf(QrScanUiState.PermissionRequired::class.java, vm.uiState.value)
    }

    @Test
    fun `onQrDecoded valid https url emits NavigateToManualEntry with url`() = runTest {
        val vm = QrScanViewModel()
        vm.events.test {
            vm.onQrDecoded("https://abc-xyz.trycloudflare.com")
            val event = awaitItem() as QrScanEvent.NavigateToManualEntry
            assertEquals("https://abc-xyz.trycloudflare.com", event.url)
        }
    }

    @Test
    fun `onQrDecoded valid http url with port emits NavigateToManualEntry`() = runTest {
        val vm = QrScanViewModel()
        vm.events.test {
            vm.onQrDecoded("http://192.168.1.50:18789")
            assertEquals("http://192.168.1.50:18789", (awaitItem() as QrScanEvent.NavigateToManualEntry).url)
        }
    }

    @Test
    fun `onQrDecoded blank string sets InvalidQr state`() {
        val vm = QrScanViewModel()
        vm.onQrDecoded("   ")
        assertInstanceOf(QrScanUiState.InvalidQr::class.java, vm.uiState.value)
    }

    @Test
    fun `onQrDecoded non-url text sets InvalidQr state`() {
        val vm = QrScanViewModel()
        vm.onQrDecoded("just some random text")
        assertInstanceOf(QrScanUiState.InvalidQr::class.java, vm.uiState.value)
    }

    @Test
    fun `onScanAgain resets to Scanning after InvalidQr`() {
        val vm = QrScanViewModel()
        vm.onQrDecoded("not a url")
        assertInstanceOf(QrScanUiState.InvalidQr::class.java, vm.uiState.value)
        vm.onScanAgain()
        assertInstanceOf(QrScanUiState.Scanning::class.java, vm.uiState.value)
    }
}
