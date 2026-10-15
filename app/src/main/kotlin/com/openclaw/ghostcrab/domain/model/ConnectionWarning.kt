package com.openclaw.ghostcrab.domain.model

import com.openclaw.ghostcrab.domain.exception.GatewayException

/**
 * Something the user should be told about that did not fail the connect, or that explains its failure
 * (ADR-0007). Carried by [GatewayConnection.Connected.warnings] and [GatewayConnection.Error.warnings].
 *
 * Additive (v1.1): new domain type, no frozen v1.0 contract changes (ADR-0005).
 */
sealed interface ConnectionWarning {

    /**
     * This phone's device identity could not be read (Keystore loss, corrupt value) and was replaced by a new one
     * during this app run. Every gateway this phone was paired with now sees a new device and shows a new pairing
     * request for it.
     */
    data object DeviceIdentityRegenerated : ConnectionWarning

    /**
     * The gateway issued a device token in `hello-ok`, but this phone's encrypted storage refused to keep it. The
     * session works; a later connect without the shared token will not find the device token.
     *
     * @param cause The storage failure; its message names the gateway, never the token.
     */
    data class DeviceTokenNotSaved(val cause: GatewayException) : ConnectionWarning
}
