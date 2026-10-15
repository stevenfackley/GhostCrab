package com.openclaw.ghostcrab.ui.connection

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
 * @param scope Where the connect runs (the ViewModel's scope).
 * @param connectionManager Its state right after a refused connect supplies the warnings that explain a prompt.
 * @param onCancelled Called after [cancel] stopped a run; the screen resets its state there.
 */
class PairingWait(
    private val scope: CoroutineScope,
    private val connectionManager: GatewayConnectionManager,
    private val onCancelled: () -> Unit,
) {
    private val wakeUps = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    /** `true` while a connect, or the approval wait it turned into, is running. */
    val isRunning: Boolean get() = job?.isActive == true

    /**
     * Connects with [connect]; on `PAIRING_REQUIRED` retries until the device is approved or the pending request
     * expires (see [connectAwaitingApproval]). Does nothing while a run is in progress: one loop at a time.
     *
     * @param onPrompt Receives every approval prompt, with the warnings that explain it.
     * @param connect The connect call; re-run for every attempt.
     * @param onFinished Receives `true` once connected, `false` when the approval window expired, or the failure.
     *   Not called when the run is cancelled.
     * @return `false` when a run was already in progress and nothing was started.
     */
    fun start(
        onPrompt: (PairingPrompt) -> Unit,
        connect: suspend () -> Unit,
        onFinished: suspend (Result<Boolean>) -> Unit,
    ): Boolean {
        if (isRunning) return false
        job = scope.launch {
            val explained = { prompt: PairingPrompt ->
                onPrompt(prompt.withWarningsFrom(connectionManager.connectionState.value))
            }
            onFinished(suspendRunCatching { connectAwaitingApproval(wakeUps, explained, connect) })
        }
        return true
    }

    /** "Check now" on the approval card: retry the connect immediately. */
    fun checkNow() {
        wakeUps.trySend(Unit)
    }

    /** "Cancel" on the approval card: stop the run, then [onCancelled]. The pending request expires on the gateway. */
    fun cancel() {
        job?.cancel()
        job = null
        onCancelled()
    }
}
