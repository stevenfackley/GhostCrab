package com.openclaw.ghostcrab.ui

import com.openclaw.ghostcrab.domain.exception.DeviceCredentialStorageException
import com.openclaw.ghostcrab.domain.exception.GatewayConnectAuthException
import com.openclaw.ghostcrab.domain.exception.GatewayPairingRequiredException
import com.openclaw.ghostcrab.ui.connection.PAIRING_TIMEOUT_MS
import com.openclaw.ghostcrab.ui.connection.PairingOutcome
import com.openclaw.ghostcrab.ui.connection.PairingPrompt
import com.openclaw.ghostcrab.ui.connection.awaitPairingApproval
import com.openclaw.ghostcrab.ui.connection.connectAwaitingApproval
import com.openclaw.ghostcrab.ui.connection.toPrompt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.GeneralSecurityException

@OptIn(ExperimentalCoroutinesApi::class)
class PairingApprovalTest {

    private val url = "ws://192.168.1.50:18789/ws"
    private val pending = GatewayPairingRequiredException(url, "req-1", "not-paired", listOf("operator.read"))

    @Test fun `retries every interval until approved and reports each newer request`() = runTest {
        var calls = 0
        val prompts = mutableListOf<PairingPrompt>()
        val outcome = awaitPairingApproval(
            first = pending,
            wakeUps = Channel(Channel.CONFLATED),
            onPrompt = { prompts.add(it) },
            attempt = {
                calls++
                if (calls < 3) throw GatewayPairingRequiredException(url, "req-$calls", "not-paired", emptyList())
            },
        )
        assertEquals(PairingOutcome.Approved, outcome)
        assertEquals(3, calls)
        assertEquals(listOf("req-1", "req-1", "req-2"), prompts.map { it.requestId })
        assertEquals(15_000, currentTime)
    }

    @Test fun `a wake-up retries without waiting`() = runTest {
        val wakeUps = Channel<Unit>(Channel.CONFLATED)
        wakeUps.trySend(Unit)
        assertEquals(PairingOutcome.Approved, awaitPairingApproval(pending, wakeUps, onPrompt = {}, attempt = {}))
        assertEquals(0, currentTime)
    }

    @Test fun `gives up when the pending request expires`() = runTest {
        val outcome =
            awaitPairingApproval(pending, Channel(Channel.CONFLATED), onPrompt = {}, attempt = { throw pending })
        assertEquals(PairingOutcome.TimedOut, outcome)
        assertEquals(PAIRING_TIMEOUT_MS, currentTime)
    }

    @Test fun `any other failure ends the wait`() = runTest {
        assertThrows<GatewayConnectAuthException> {
            awaitPairingApproval(pending, Channel(Channel.CONFLATED), onPrompt = {}, attempt = {
                throw GatewayConnectAuthException(url, "AUTH_DEVICE_TOKEN_MISMATCH", "revoked", null)
            })
        }
    }

    @Test fun `a credential-storage failure ends the wait at once, although it is retryable`() = runTest {
        var calls = 0
        val storage = DeviceCredentialStorageException("open the device identity", GeneralSecurityException("ks"))
        assertTrue(storage.isRetryable)
        assertThrows<DeviceCredentialStorageException> {
            awaitPairingApproval(pending, Channel(Channel.CONFLATED), onPrompt = {}, attempt = {
                calls++
                throw storage
            })
        }
        assertEquals(1, calls)
    }

    @Test fun `an outer timeout propagates as cancellation, never as an expired approval`() = runTest {
        assertThrows<TimeoutCancellationException> {
            withTimeout(12_000) {
                awaitPairingApproval(pending, Channel(Channel.CONFLATED), onPrompt = {}, attempt = { throw pending })
            }
        }
        assertEquals(12_000, currentTime)
    }

    @Test fun `cancelling the wait stops the retries`() = runTest {
        var calls = 0
        val wait = launch {
            awaitPairingApproval(pending, Channel(Channel.CONFLATED), onPrompt = {}, attempt = {
                calls++
                throw pending
            })
        }
        advanceTimeBy(11_000)
        wait.cancel()
        advanceTimeBy(60_000)
        assertEquals(2, calls)
        assertTrue(wait.isCancelled)
    }

    @Test fun `the prompt names the exact approve command`() {
        assertEquals("openclaw devices approve req-1", pending.toPrompt().approveCommand)
        assertEquals("openclaw devices list", PairingPrompt(null, "not-paired", emptyList()).approveCommand)
    }

    @Test fun `connectAwaitingApproval connects at once when no approval is needed`() = runTest {
        assertTrue(connectAwaitingApproval(Channel(Channel.CONFLATED), onPrompt = {}) {})
        assertEquals(0, currentTime)
    }
}
