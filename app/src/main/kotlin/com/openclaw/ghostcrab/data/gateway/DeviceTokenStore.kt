package com.openclaw.ghostcrab.data.gateway

import com.openclaw.ghostcrab.domain.util.GatewayUrl
import kotlinx.serialization.Serializable
import java.util.Locale

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
     * Never throws for storage trouble: an entry the Keystore cannot decrypt or that does not decode reads as
     * `null` and is removed (best effort), so the next connect falls back to the shared token and the gateway
     * issues a new device token.
     *
     * @param gatewayKey Key from [gatewayKeyOf].
     * @return The stored token, or `null` when none or unreadable.
     */
    suspend fun loadDeviceToken(gatewayKey: String): StoredDeviceToken?

    /**
     * @param gatewayKey Key from [gatewayKeyOf].
     * @param token The token to store, replacing any previous one.
     * @throws com.openclaw.ghostcrab.domain.exception.DeviceCredentialStorageException if the encrypted store
     *   refuses the write (the message names the gateway, never the token).
     */
    suspend fun saveDeviceToken(gatewayKey: String, token: StoredDeviceToken)

    /**
     * @param gatewayKey Key from [gatewayKeyOf]. No-op when nothing is stored.
     * @throws com.openclaw.ghostcrab.domain.exception.DeviceCredentialStorageException if the encrypted store
     *   refuses the removal.
     */
    suspend fun clearDeviceToken(gatewayKey: String)
}

/**
 * The key a gateway's device token is stored under: one token per `scheme://host:port`. A path, query or fragment
 * in [url] is ignored (`https://gw.example/ws` and `https://gw.example` share a key).
 *
 * `http(s)` URLs are canonicalised by [GatewayUrl.parse], `ws(s)` URLs by [GatewayUrl.fromWebSocketUrl], so the same
 * gateway written as `ws://gw:18789/ws` and `http://gw:18789` (or `wss://gw/ws` and `https://gw`) shares one key and
 * keys saved before `ws(s)` input was accepted stay valid. Anything else falls back to the trimmed text.
 *
 * @param url A gateway base URL as the user or a profile gave it.
 * @return The canonical `scheme://host:port` ([GatewayUrl]) with the host lowercased (host names are
 *   case-insensitive, so `GW.local` and `gw.local` share one token), or the trimmed input without trailing `/`
 *   when it does not parse.
 */
fun gatewayKeyOf(url: String): String =
    (GatewayUrl.parse(url) ?: GatewayUrl.fromWebSocketUrl(url))
        ?.let { it.copy(host = it.host.lowercase(Locale.ROOT)).toString() }
        ?: url.trim().trimEnd('/')
