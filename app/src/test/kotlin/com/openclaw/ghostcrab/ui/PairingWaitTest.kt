package com.openclaw.ghostcrab.ui

import com.openclaw.ghostcrab.domain.exception.GatewayPairingRequiredException
import com.openclaw.ghostcrab.domain.model.AuthRequirement
import com.openclaw.ghostcrab.domain.model.ConnectionWarning
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import com.openclaw.ghostcrab.domain.repository.GatewayConnectionManager
import com.openclaw.ghostcrab.ui.connection.PAIRING_RETRY_INTERVAL_MS
import com.openclaw.ghostcrab.ui.connection.PAIRING_TIMEOUT_MS
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PairingWaitTest {

    private val url = "http://192.168.1.50:18789"
    private val pending = GatewayPairingRequiredException("ws://192.168.1.50:18789/ws", "req-1", "not-paired", listOf())

    /** The manager right after the gateway refused [url] with [pending], as the real one reports it. */
    private val manager = WaitConnectionManager(MutableStateFlow(GatewayConnection.Error(url, pending)))

    private var cancelled = 0
    private var finished: Result<Boolean>? = null

    private fun TestScope.waitIn() = PairingWait(backgroundScope, manager) { cancelled++ }

    private fun PairingWait.startRefused(onAttempt: () -> Unit = {}): Boolean = start(
        url = url,
        onPrompt = {},
        connect = {
            onAttempt()
            throw pending
        },
        onFinished = { finished = it },
    )

    @Test fun `a second start while one runs is refused, so only one loop polls`() = runTest {
        var attempts = 0
        val wait = waitIn()
        val first = wait.startRefused { attempts++ }
        val second = wait.start(url, onPrompt = {}, connect = { attempts += 100 }, onFinished = {})
        runCurrent()
        advanceTimeBy(PAIRING_RETRY_INTERVAL_MS + 1)

        assertTrue(first)
        assertFalse(second)
        assertEquals(2, attempts)
        wait.cancel()
    }

    @Test fun `cancel stops the retries, resets the screen and disconnects the refused connect`() = runTest {
        var attempts = 0
        val wait = waitIn()
        wait.startRefused { attempts++ }
        runCurrent()

        wait.cancel()
        advanceTimeBy(PAIRING_RETRY_INTERVAL_MS * 10)

        assertEquals(1, attempts)
        assertEquals(1, cancelled)
        assertNull(finished)
        assertFalse(wait.isRunning)
        assertEquals(1, manager.disconnects)
        assertEquals(GatewayConnection.Disconnected, manager.state.value)
    }

    @Test fun `cancel while nothing runs changes nothing`() = runTest {
        val wait = waitIn()
        wait.cancel()
        runCurrent()
        assertEquals(0, cancelled)
        assertEquals(0, manager.disconnects)
    }

    @Test fun `a session opened elsewhere ends the wait without another connect or a disconnect`() = runTest {
        var attempts = 0
        val wait = waitIn()
        wait.startRefused { attempts++ }
        runCurrent()

        manager.state.value = connectedElsewhere
        advanceTimeBy(PAIRING_RETRY_INTERVAL_MS * 3)

        assertEquals(1, attempts)
        assertEquals(1, cancelled)
        assertNull(finished)
        assertEquals(0, manager.disconnects)
        assertFalse(wait.isRunning)
    }

    @Test fun `an expired wait disconnects so no stale pairing error stays behind`() = runTest {
        val wait = waitIn()
        wait.startRefused()
        advanceTimeBy(PAIRING_TIMEOUT_MS + 1)

        assertEquals(false, finished?.getOrNull())
        assertEquals(1, manager.disconnects)
    }

    @Test fun `a refusal that already happened starts the wait without a first connect`() = runTest {
        var attempts = 0
        val prompts = mutableListOf<PairingPrompt>()
        val wait = waitIn()
        wait.start(url, onPrompt = { prompts += it }, connect = { attempts++ }, first = pending, onFinished = {
            finished = it
        })
        runCurrent()
        assertEquals(0, attempts)
        assertEquals(listOf("req-1"), prompts.map { it.requestId })

        advanceTimeBy(PAIRING_RETRY_INTERVAL_MS + 1)

        assertEquals(1, attempts)
        assertEquals(true, finished?.getOrNull())
    }

    @Test fun `check now retries at once and a success finishes the run`() = runTest {
        var attempts = 0
        val wait = waitIn()
        wait.start(url, onPrompt = {}, connect = { if (attempts++ == 0) throw pending }) { finished = it }
        runCurrent()

        wait.checkNow()
        runCurrent()

        assertEquals(2, attempts)
        assertEquals(true, finished?.getOrNull())
        assertFalse(wait.isRunning)
    }

    @Test fun `each prompt carries the warnings and the URL of the refused connect`() = runTest {
        manager.state.value = GatewayConnection.Error(url, pending, listOf(ConnectionWarning.DeviceIdentityRegenerated))
        val prompts = mutableListOf<PairingPrompt>()
        val wait = waitIn()
        wait.start(url, onPrompt = { prompts += it }, connect = { throw pending }, onFinished = {})
        runCurrent()

        assertEquals(listOf(ConnectionWarning.DeviceIdentityRegenerated), prompts.single().warnings)
        assertEquals(pending.url, prompts.single().url)
        wait.cancel()
    }

    private val connectedElsewhere = GatewayConnection.Connected(
        url = "http://192.168.1.51:18789",
        displayName = "Other",
        version = "2026.10.3",
        authRequirement = AuthRequirement.Token,
        isHttps = false,
        capabilities = emptyList(),
    )
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
