package com.openclaw.ghostcrab.ui.connection

import com.openclaw.ghostcrab.domain.exception.GatewayPairingRequiredException
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import com.openclaw.ghostcrab.domain.repository.GatewayConnectionManager
import com.openclaw.ghostcrab.domain.util.suspendRunCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * One connect at a time for a screen, including the device-approval wait it may turn into (ADR-0007). Shared by
 * the Manual Entry and saved-gateway ViewModels; owns the running job and the approval card's "Check now" and
 * "Cancel".
 *
 * A wait never fights another screen: each retry first checks that the manager still holds this wait's own
 * `PAIRING_REQUIRED` error for its URL. Anything else (a session or connect started elsewhere, a disconnect, another
 * wait's refusal) ends this wait quietly, as [cancel] does, without touching the connection.
 *
 * @param scope Where the connect runs (the ViewModel's scope).
 * @param connectionManager Supplies the warnings that explain a prompt and the state each retry checks.
 * @param onCancelled Called when a run ends without a result ([cancel], or superseded); the screen resets its state.
 */
class PairingWait(
    private val scope: CoroutineScope,
    private val connectionManager: GatewayConnectionManager,
    private val onCancelled: () -> Unit,
) {
    private val wakeUps = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null
    private var url: String? = null

    /** `true` while a connect, or the approval wait it turned into, is running. */
    val isRunning: Boolean get() = job?.isActive == true

    /**
     * Connects with [connect]; on `PAIRING_REQUIRED` retries until the device is approved or the pending request
     * expires (see [awaitPairingApproval]). Does nothing while a run is in progress: one loop at a time. An expired
     * wait disconnects, so no stale `Error(PAIRING_REQUIRED)` stays behind.
     *
     * @param url The base URL [connect] connects to (the manager reports its errors under it).
     * @param onPrompt Receives every approval prompt, with the warnings that explain it.
     * @param connect The connect call; re-run for every attempt.
     * @param first A refusal that already happened (the dashboard path): the wait starts with it instead of a
     *   first connect.
     * @param onFinished Receives `true` once connected, `false` when the approval window expired, or the failure.
     *   Not called when the run is cancelled or superseded.
     * @return `false` when a run was already in progress and nothing was started.
     */
    fun start(
        url: String,
        onPrompt: (PairingPrompt) -> Unit,
        connect: suspend () -> Unit,
        first: GatewayPairingRequiredException? = null,
        onFinished: suspend (Result<Boolean>) -> Unit,
    ): Boolean {
        if (isRunning) return false
        this.url = url
        job = scope.launch {
            val explained = { prompt: PairingPrompt ->
                onPrompt(prompt.withWarningsFrom(connectionManager.connectionState.value))
            }
            val result = suspendRunCatching { connectOrWait(url, first, explained, connect) }
            if (result.exceptionOrNull() is PairingWaitSupersededException) {
                onCancelled()
                return@launch
            }
            if (result.getOrNull() == false && awaitingApproval(url)) connectionManager.disconnect()
            onFinished(result)
        }
        return true
    }

    /** "Check now" on the approval card: retry the connect immediately. */
    fun checkNow() {
        wakeUps.trySend(Unit)
    }

    /**
     * "Cancel" on the approval card, or the screen being left: stops the run, calls [onCancelled] and, once the run
     * has ended, disconnects if the manager still holds this wait's `PAIRING_REQUIRED` error. The pending request
     * expires on the gateway. No-op while nothing runs.
     */
    fun cancel() {
        val running = job?.takeIf { it.isActive } ?: return
        val waitedFor = url
        job = null
        running.cancel()
        onCancelled()
        scope.launch {
            running.join()
            if (waitedFor != null && awaitingApproval(waitedFor)) connectionManager.disconnect()
        }
    }

    private suspend fun connectOrWait(
        url: String,
        first: GatewayPairingRequiredException?,
        onPrompt: (PairingPrompt) -> Unit,
        connect: suspend () -> Unit,
    ): Boolean {
        val refusal = first ?: try {
            connect()
            return true
        } catch (e: GatewayPairingRequiredException) {
            e
        }
        val retry: suspend () -> Unit = {
            if (!awaitingApproval(url)) throw PairingWaitSupersededException()
            connect()
        }
        return awaitPairingApproval(refusal, wakeUps, onPrompt, retry) == PairingOutcome.Approved
    }

    /** `true` while the manager's state is still the `PAIRING_REQUIRED` error this wait is about. */
    private fun awaitingApproval(url: String): Boolean {
        val state = connectionManager.connectionState.value
        return state is GatewayConnection.Error && state.url == url && state.cause is GatewayPairingRequiredException
    }
}

/** Another screen acted on the connection since the last attempt; the wait ends without retrying. */
private class PairingWaitSupersededException : Exception("The approval wait was superseded by another connect")
