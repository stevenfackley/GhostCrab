package com.openclaw.ghostcrab.ui

import com.openclaw.ghostcrab.domain.exception.GatewayPairingRequiredException
import com.openclaw.ghostcrab.domain.model.AuthRequirement
import com.openclaw.ghostcrab.domain.model.ConnectionWarning
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import com.openclaw.ghostcrab.domain.repository.GatewayConnectionManager
import com.openclaw.ghostcrab.ui.connection.PAIRING_RETRY_INTERVAL_MS
import com.openclaw.ghostcrab.ui.connection.PairingPrompt
import com.openclaw.ghostcrab.ui.connection.PairingWait
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PairingWaitTest {

    private val url = "http://192.168.1.50:18789"
    private val pending = GatewayPairingRequiredException("ws://192.168.1.50:18789/ws", "req-1", "not-paired", listOf())
    private val manager = WaitConnectionManager(MutableStateFlow(GatewayConnection.Error(url, pending)))

    private fun TestScope.waitIn(onCancelled: () -> Unit = {}) =
        PairingWait(backgroundScope, manager, onCancelled)

    @Test fun `a second start while one runs is refused, so only one loop polls`() = runTest {
        var attempts = 0
        val wait = waitIn()
        val first = wait.start(onPrompt = {}, connect = { attempts++; throw pending }, onFinished = {})
        val second = wait.start(onPrompt = {}, connect = { attempts += 100 }, onFinished = {})
        runCurrent()
        advanceTimeBy(PAIRING_RETRY_INTERVAL_MS + 1)

        assertTrue(first)
        assertFalse(second)
        assertEquals(2, attempts)
        wait.cancel()
    }

    @Test fun `cancel stops the retries and resets the screen`() = runTest {
        var attempts = 0
        var cancelled = false
        var finished = false
        val wait = waitIn { cancelled = true }
        wait.start(onPrompt = {}, connect = { attempts++; throw pending }, onFinished = { finished = true })
        runCurrent()

        wait.cancel()
        advanceTimeBy(PAIRING_RETRY_INTERVAL_MS * 10)

        assertEquals(1, attempts)
        assertTrue(cancelled)
        assertFalse(finished)
        assertFalse(wait.isRunning)
    }

    @Test fun `check now retries at once and a success finishes the run`() = runTest {
        var attempts = 0
        var result: Result<Boolean>? = null
        val wait = waitIn()
        wait.start(onPrompt = {}, connect = { if (attempts++ == 0) throw pending }, onFinished = { result = it })
        runCurrent()

        wait.checkNow()
        runCurrent()

        assertEquals(2, attempts)
        assertEquals(true, result?.getOrNull())
        assertFalse(wait.isRunning)
    }

    @Test fun `each prompt carries the warnings of the refused connect`() = runTest {
        manager.state.value = GatewayConnection.Error(url, pending, listOf(ConnectionWarning.DeviceIdentityRegenerated))
        val prompts = mutableListOf<PairingPrompt>()
        val wait = waitIn()
        wait.start(onPrompt = { prompts += it }, connect = { throw pending }, onFinished = {})
        runCurrent()

        assertEquals(listOf(ConnectionWarning.DeviceIdentityRegenerated), prompts.single().warnings)
        wait.cancel()
    }
}

/** Only the state matters to [PairingWait]; connects come from the test's lambdas. */
private class WaitConnectionManager(val state: MutableStateFlow<GatewayConnection>) : GatewayConnectionManager {
    var disconnects = 0
        private set

    override val connectionState: StateFlow<GatewayConnection> = state
    override suspend fun probeAuth(url: String) = AuthRequirement.None
    override suspend fun connect(url: String, token: String?) = Unit
    override suspend fun disconnect() {
        disconnects++
        state.value = GatewayConnection.Disconnected
    }
}
