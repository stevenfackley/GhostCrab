package com.openclaw.ghostcrab.ui.connection

import com.openclaw.ghostcrab.domain.exception.GatewayPairingRequiredException
import com.openclaw.ghostcrab.domain.model.ConnectionWarning
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeoutOrNull

/** How often a connect is re-attempted while the device awaits approval. */
const val PAIRING_RETRY_INTERVAL_MS: Long = 5_000L

/** Upstream expires a pending device-pairing request after 5 minutes. */
const val PAIRING_TIMEOUT_MS: Long = 5 * 60_000L

/** Shown when no approval arrived before the pending request expired. */
const val PAIRING_TIMEOUT_MESSAGE: String =
    "Device approval not received within 5 minutes; the pending request expired. On the gateway host run " +
        "`openclaw devices list`, approve this device, then connect again (PAIRING_REQUIRED)."

/**
 * What the gateway owner must do to approve this device (ADR-0007 decision 7).
 *
 * @param requestId The pending request, when the gateway sent one.
 * @param reason `not-paired`, `scope-upgrade`, `role-upgrade` or `metadata-upgrade`.
 * @param requestedScopes The scopes this device asked for.
 * @param warnings What explains the request, e.g. [ConnectionWarning.DeviceIdentityRegenerated] when an
 *   already-paired phone has to be approved again. Empty when there is nothing to explain.
 */
data class PairingPrompt(
    val requestId: String?,
    val reason: String,
    val requestedScopes: List<String>,
    val warnings: List<ConnectionWarning> = emptyList(),
) {
    /** The exact command for the gateway host. */
    val approveCommand: String get() = requestId?.let { "openclaw devices approve $it" } ?: "openclaw devices list"
}

/** @return The prompt for this pairing requirement. */
fun GatewayPairingRequiredException.toPrompt(): PairingPrompt = PairingPrompt(requestId, reason, requestedScopes)

/**
 * @param state The connection manager's state right after the refused connect.
 * @return This prompt with the warnings of that [GatewayConnection.Error]; unchanged for any other state.
 */
fun PairingPrompt.withWarningsFrom(state: GatewayConnection): PairingPrompt =
    (state as? GatewayConnection.Error)?.let { copy(warnings = it.warnings) } ?: this

/** Result of [awaitPairingApproval]. */
enum class PairingOutcome { Approved, TimedOut }

/**
 * Re-runs [attempt] every [intervalMs] — or at once when [wakeUps] delivers ("Check now") — while it fails with
 * [GatewayPairingRequiredException], passing each prompt to [onPrompt]. Nothing is approved from the phone.
 *
 * The gateway closes an unpaired socket right after `PAIRING_REQUIRED` and tells only paired admin sockets about
 * the approval, so reconnecting is the only way to notice it; the `requestId` stays the same across attempts.
 * Every other failure ends the wait, including a retryable one such as
 * [com.openclaw.ghostcrab.domain.exception.DeviceCredentialStorageException]: its retry is the user's call, never
 * this loop's. Cancelling the caller stops the wait; a caller's own timeout propagates as cancellation, it is never
 * reported as [PairingOutcome.TimedOut].
 *
 * @param first The failure that started the wait; its prompt is reported first.
 * @param wakeUps Each element triggers an immediate retry.
 * @param onPrompt Receives the first prompt and every newer one the gateway reports.
 * @param attempt The connect to retry.
 * @param intervalMs Delay between attempts.
 * @param timeoutMs Overall budget (the pending request's lifetime).
 * @return [PairingOutcome.Approved] once [attempt] succeeds, [PairingOutcome.TimedOut] after [timeoutMs].
 * @throws Exception whatever [attempt] throws other than [GatewayPairingRequiredException] (e.g. a revoked token).
 */
suspend fun awaitPairingApproval(
    first: GatewayPairingRequiredException,
    wakeUps: ReceiveChannel<Unit>,
    onPrompt: (PairingPrompt) -> Unit,
    attempt: suspend () -> Unit,
    intervalMs: Long = PAIRING_RETRY_INTERVAL_MS,
    timeoutMs: Long = PAIRING_TIMEOUT_MS,
): PairingOutcome {
    onPrompt(first.toPrompt())
    val approved = withTimeoutOrNull(timeoutMs) {
        var done = false
        while (!done) {
            withTimeoutOrNull(intervalMs) { wakeUps.receive() }
            done = try {
                attempt()
                true
            } catch (e: GatewayPairingRequiredException) {
                onPrompt(e.toPrompt())
                false
            }
        }
        true
    }
    return if (approved == true) PairingOutcome.Approved else PairingOutcome.TimedOut
}

/**
 * Connects once; on `PAIRING_REQUIRED` waits for approval with [awaitPairingApproval].
 *
 * @param wakeUps "Check now" signals.
 * @param onPrompt Receives approval prompts.
 * @param connect The connect call.
 * @return `true` once connected, `false` when the approval window expired.
 * @throws Exception whatever [connect] throws other than [GatewayPairingRequiredException].
 */
suspend fun connectAwaitingApproval(
    wakeUps: ReceiveChannel<Unit>,
    onPrompt: (PairingPrompt) -> Unit,
    connect: suspend () -> Unit,
): Boolean = try {
    connect()
    true
} catch (e: GatewayPairingRequiredException) {
    awaitPairingApproval(e, wakeUps, onPrompt, connect) == PairingOutcome.Approved
}
