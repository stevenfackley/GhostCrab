package com.openclaw.ghostcrab.data.gateway

import com.openclaw.ghostcrab.domain.util.GatewayUrl
import kotlinx.serialization.Serializable

/**
 * A device token the gateway issued in `hello-ok.auth.deviceToken`.
 *
 * @param token The bearer for `connect.params.auth.deviceToken`. Sensitive — never log.
 * @param scopes The scopes it was granted (reused when reconnecting with it).
 * @param role The role it was issued for (`operator`).
 * @param deviceId The [DeviceIdentity.deviceId] it belongs to; a token for another identity is ignored.
 */
@Serializable
data class StoredDeviceToken(
    val token: String,
    val scopes: List<String>,
    val role: String,
    val deviceId: String,
) {
    override fun toString(): String =
        "StoredDeviceToken(token=[REDACTED], scopes=$scopes, role=$role, deviceId=$deviceId)"
}

/** Per-gateway device-token persistence, keyed by [gatewayKeyOf]. */
interface DeviceTokenStore {
    /**
     * @param gatewayKey Key from [gatewayKeyOf].
     * @return The stored token, or `null` when none (or when the entry was unreadable; it is then removed).
     */
    suspend fun loadDeviceToken(gatewayKey: String): StoredDeviceToken?

    /**
     * @param gatewayKey Key from [gatewayKeyOf].
     * @param token The token to store, replacing any previous one.
     */
    suspend fun saveDeviceToken(gatewayKey: String, token: StoredDeviceToken)

    /** @param gatewayKey Key from [gatewayKeyOf]. No-op when nothing is stored. */
    suspend fun clearDeviceToken(gatewayKey: String)
}

/**
 * @param url A gateway base URL as the user or a profile gave it.
 * @return The canonical `scheme://host:port` ([GatewayUrl]), or the trimmed input without trailing `/` when it
 *   does not parse.
 */
fun gatewayKeyOf(url: String): String = GatewayUrl.parse(url)?.toString() ?: url.trim().trimEnd('/')
