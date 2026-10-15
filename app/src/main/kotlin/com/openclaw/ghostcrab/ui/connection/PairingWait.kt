package com.openclaw.ghostcrab.ui.connection

import com.openclaw.ghostcrab.domain.exception.GatewayPairingRequiredException
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import com.openclaw.ghostcrab.domain.repository.GatewayConnectionManager
import com.openclaw.ghostcrab.domain.util.suspendRunCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * @param cleanupScope Where the disconnect after [cancel] / [release] runs. Must outlive [scope]: the ViewModel's
 *   scope is already cancelled when its clearing calls [release]. Production passes [pairingCleanupScope].
 * @param onCancelled Called when a run ends without a result ([cancel], or superseded); the screen resets its state.
 */
class PairingWait(
    private val scope: CoroutineScope,
    private val connectionManager: GatewayConnectionManager,
    private val cleanupScope: CoroutineScope,
    private val onCancelled: () -> Unit,
) {
    private val wakeUps = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null
    private var url: String? = null

    /** `true` once the run has reached `onFinished` (e.g. saving the approved profile); [cancel] no longer applies. */
    private var finishing = false

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
     *   Not called when the run is cancelled or superseded. Once it has started, [cancel] is ignored: a Cancel
     *   tapped during the post-approval save must not abort it and leave the device connected but unsaved.
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
        finishing = false
        // A "Check now" left over from before this run must not cut its first interval.
        wakeUps.tryReceive()
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
            finishing = true
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
     * expires on the gateway. No-op while nothing runs, and once the run has reached `onFinished` (see [start]).
     */
    fun cancel() {
        val running = job?.takeIf { it.isActive && !finishing } ?: return
        job = null
        running.cancel()
        onCancelled()
        disconnectIfStillWaiting(after = running)
    }

    /**
     * The owner is going away: call when the ViewModel is cleared (a closeable or `onCleared`), when its scope has
     * already cancelled the run. Like [cancel], disconnects if the manager still holds this wait's `PAIRING_REQUIRED`
     * error, so no stale error outlives the screen; [onCancelled] is not called (no screen is left to reset). No-op
     * while nothing ran.
     */
    fun release() {
        val running = job ?: return
        job = null
        running.cancel()
        disconnectIfStillWaiting(after = running)
    }

    /**
     * Runs in [cleanupScope] under [NonCancellable]: the owner's scope may be gone, and the disconnect must still
     * happen once [after] has ended.
     */
    private fun disconnectIfStillWaiting(after: Job) {
        val waitedFor = url ?: return
        cleanupScope.launch {
            withContext(NonCancellable) {
                after.join()
                if (awaitingApproval(waitedFor)) connectionManager.disconnect()
            }
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

/**
 * The production [PairingWait] cleanup scope: never cancelled, so the short disconnect after a cleared ViewModel still
 * runs. It runs nothing else.
 *
 * @return A fresh scope on the main dispatcher. `Dispatchers.Main` is resolved when the cleanup runs, not here, so
 *   building a ViewModel where no main dispatcher exists (a JVM test of the Koin graph) does not fail.
 */
fun pairingCleanupScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

/** Another screen acted on the connection since the last attempt; the wait ends without retrying. */
private class PairingWaitSupersededException : Exception("The approval wait was superseded by another connect")
