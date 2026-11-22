package com.openclaw.ghostcrab.ui.connection

import com.openclaw.ghostcrab.domain.exception.GatewayPairingRequiredException
import com.openclaw.ghostcrab.domain.model.ConnectionProfile
import com.openclaw.ghostcrab.domain.repository.ConnectionProfileRepository
import com.openclaw.ghostcrab.domain.repository.GatewayConnectionManager

/**
 * Connects to a saved [profile] with its stored token through this wait: the saved-gateway connect shared by the
 * picker and Scan.
 *
 * The token is read inside the run [PairingWait.start] tracks, so the run is [PairingWait.isRunning] from the tap and
 * [PairingWait.cancel] (leaving the screen) also stops a read still in flight: no connect starts after that. It is
 * read once, by the first attempt, and reused by every retry (a Keystore read per retry is wasteful); a profile
 * without a token never touches the Keystore. The token is held for the run, as the session holds it once connected.
 *
 * A failed read (`ProfileNeedsReauthException` after a Keystore reset) ends the run through [onFinished]. From a
 * tap that is before anything connects. With [first] (the dashboard's refusal) the first attempt is the first
 * retry, so the approval card shows until then, and the manager keeps that refusal: nothing disconnects it.
 *
 * @param profile The saved profile to connect to.
 * @param profileRepository Supplies the stored token.
 * @param connectionManager Connects with it, on every attempt.
 * @param onPrompt Receives every approval prompt (see [PairingWait.start]).
 * @param first A refusal that already happened, so the wait starts with it (see [PairingWait.start]).
 * @param onFinished Receives the run's result, a failed token read included (see [PairingWait.start]).
 * @return `false` when a run was already in progress: nothing was started and no token was read.
 */
internal fun PairingWait.startSavedProfileConnect(
    profile: ConnectionProfile,
    profileRepository: ConnectionProfileRepository,
    connectionManager: GatewayConnectionManager,
    onPrompt: (PairingPrompt) -> Unit,
    first: GatewayPairingRequiredException? = null,
    onFinished: suspend (Result<Boolean>) -> Unit,
): Boolean {
    var token: String? = null
    var tokenRead = false
    return start(
        url = profile.url,
        onPrompt = onPrompt,
        connect = {
            if (!tokenRead) {
                token = if (profile.hasToken) profileRepository.getToken(profile.id) else null
                tokenRead = true
            }
            connectionManager.connect(profile.url, token)
        },
        first = first,
        onFinished = onFinished,
    )
}
