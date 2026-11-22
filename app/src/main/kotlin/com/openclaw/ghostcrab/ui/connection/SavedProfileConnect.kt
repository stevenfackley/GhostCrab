package com.openclaw.ghostcrab.ui.connection

import com.openclaw.ghostcrab.domain.exception.GatewayPairingRequiredException
import com.openclaw.ghostcrab.domain.model.ConnectionProfile
import com.openclaw.ghostcrab.domain.repository.ConnectionProfileRepository
import com.openclaw.ghostcrab.domain.repository.GatewayConnectionManager
import com.openclaw.ghostcrab.domain.util.suspendRunCatching

/**
 * Connects to a saved [profile] with its stored token through this wait: the saved-gateway connect shared by the
 * picker and Scan.
 *
 * The token is read once, before the wait starts. A Keystore read per retry is wasteful, and a read failing
 * mid-wait would end the run with the manager still holding the wait's `PAIRING_REQUIRED` error. A failed read
 * (`ProfileNeedsReauthException` after a Keystore reset) goes to [onTokenUnreadable] and nothing connects, so there
 * is nothing to disconnect. A profile without a token never touches the Keystore.
 *
 * @param profile The saved profile to connect to.
 * @param profileRepository Supplies the stored token.
 * @param connectionManager Connects with it, on every attempt.
 * @param onPrompt Receives every approval prompt (see [PairingWait.start]).
 * @param onTokenUnreadable Receives the token read's failure; the wait is not started.
 * @param first A refusal that already happened, so the wait starts with it (see [PairingWait.start]).
 * @param onFinished Receives the run's result (see [PairingWait.start]).
 */
internal suspend fun PairingWait.startSavedProfileConnect(
    profile: ConnectionProfile,
    profileRepository: ConnectionProfileRepository,
    connectionManager: GatewayConnectionManager,
    onPrompt: (PairingPrompt) -> Unit,
    onTokenUnreadable: suspend (Throwable) -> Unit,
    first: GatewayPairingRequiredException? = null,
    onFinished: suspend (Result<Boolean>) -> Unit,
) {
    val token = suspendRunCatching { if (profile.hasToken) profileRepository.getToken(profile.id) else null }
        .getOrElse { e ->
            onTokenUnreadable(e)
            return
        }
    start(
        url = profile.url,
        onPrompt = onPrompt,
        connect = { connectionManager.connect(profile.url, token) },
        first = first,
        onFinished = onFinished,
    )
}
