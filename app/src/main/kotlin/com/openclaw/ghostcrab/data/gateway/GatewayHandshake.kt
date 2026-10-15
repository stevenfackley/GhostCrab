package com.openclaw.ghostcrab.data.gateway

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `connect.params.client` (ADR-0007). [id] and [mode] must be values upstream's allow-lists accept (checked before
 * auth: anything else is `INVALID_REQUEST invalid connect params`, close 1008); [version] and [displayName] stay
 * honest. [platform] is bound into the device signature and pinned when the device is approved: changing it
 * re-triggers pairing (`metadata-upgrade`), so it is a constant per app, never derived from the device.
 *
 * @param id Upstream client id.
 * @param version GhostCrab's version name.
 * @param platform `android`.
 * @param displayName Shown in the gateway's pending-pairing list.
 * @param mode Upstream client mode; `ui` for an interactive operator app.
 * @param deviceFamily Optional; bound into the signature and pinned at approval, so it must never change.
 * @param locale BCP-47 tag for `connect.params.locale`.
 */
data class ClientInfo(
    val id: String,
    val version: String,
    val platform: String,
    val displayName: String,
    val mode: String = "ui",
    val deviceFamily: String? = null,
    val locale: String = "en-US",
) {
    /** `connect.params.userAgent`. */
    val userAgent: String get() = "ghostcrab-$platform/$version"
}

/** Credential for `connect.params.auth`. Every `toString` redacts the secret. */
sealed interface ConnectAuth {
    /** The value bound into the device signature's token field, or `null`. Sensitive — never log. */
    val signatureToken: String?

    /**
     * The gateway's shared token (`auth.token`).
     *
     * @param token The token. Sensitive — never log.
     */
    class SharedToken(val token: String) : ConnectAuth {
        override val signatureToken: String get() = token
        override fun toString(): String = "SharedToken([REDACTED])"
    }

    /**
     * A device token this gateway issued earlier (`auth.deviceToken`), reused with its granted [scopes].
     *
     * @param token The device token. Sensitive — never log.
     * @param scopes The scopes it was issued with; requested again as `connect.params.scopes`.
     */
    class DeviceToken(val token: String, val scopes: List<String>) : ConnectAuth {
        override val signatureToken: String get() = token
        override fun toString(): String = "DeviceToken([REDACTED], scopes=$scopes)"
    }

    /** No credential: `auth.mode: none` gateways and the auth probe. */
    data object None : ConnectAuth {
        override val signatureToken: String? get() = null
    }
}

/**
 * Upstream `selectGatewayConnectAuth` order for an operator client: explicit shared token, then the stored device
 * token, else nothing. Setup-code bootstrap tokens are not a credential here: the gateway accepts them only for
 * `role: "node"` (verified 2026-10-15, ADR-0007).
 *
 * @param sharedToken Token the user or profile supplied.
 * @param stored This gateway's stored device token (already filtered to the current identity).
 * @return The credential to send.
 */
fun selectConnectAuth(sharedToken: String?, stored: StoredDeviceToken?): ConnectAuth = when {
    sharedToken != null -> ConnectAuth.SharedToken(sharedToken)
    stored != null -> ConnectAuth.DeviceToken(stored.token, stored.scopes)
    else -> ConnectAuth.None
}

/** The `connect` exchange (ADR-0007 decision 3). The connect frame is never logged: it carries the credential. */
object GatewayHandshake {

    /** GhostCrab connects as an operator client. */
    const val ROLE_OPERATOR = "operator"

    /** Scopes requested unless a stored device token says otherwise (`config.*` and `skills.install` need admin). */
    val OPERATOR_SCOPES: List<String> = listOf("operator.read", "operator.write", "operator.admin")

    /** Time for the gateway to answer `connect`. */
    const val CONNECT_TIMEOUT_MS: Long = 15_000L

    /**
     * Waits for the challenge, sends the signed `connect`, parses `hello-ok`. A caller's own timeout or
     * cancellation propagates unchanged (the RPC client never turns it into a gateway error).
     *
     * @param rpc A freshly opened socket (nothing sent yet).
     * @param client This app's client info.
     * @param auth The credential from [selectConnectAuth].
     * @param identity This install's device identity.
     * @return The parsed hello.
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayPairingRequiredException while the device awaits approval.
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayConnectAuthException for credential/device rejections.
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayRpcException for any other refusal (e.g. a client id
     *   outside the allow-list: `INVALID_REQUEST invalid connect params`).
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayProtocolException for a malformed challenge or hello.
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayTimeoutException or
     *   [com.openclaw.ghostcrab.domain.exception.GatewayUnreachableException] on transport failure.
     */
    suspend fun perform(
        rpc: GatewayRpcClient,
        client: ClientInfo,
        auth: ConnectAuth,
        identity: DeviceIdentity,
    ): HelloOk {
        val challenge = ConnectChallenge.parse(rpc.endpoint, rpc.awaitChallenge().payload)
        val payload = rpc.request("connect", connectParams(client, auth, identity, challenge), CONNECT_TIMEOUT_MS)
        return parseHello(rpc.endpoint, payload)
    }

    /**
     * @param endpoint For error messages.
     * @param payload The `connect` response payload.
     * @return The parsed hello.
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayProtocolException if it is not a protocol-4 `hello-ok`.
     */
    fun parseHello(endpoint: String, payload: JsonElement): HelloOk = parseHelloOk(endpoint, payload)

    /**
     * @param client This app's client info.
     * @param auth The credential; also bound into the signature.
     * @param identity Signs the v3 payload.
     * @param challenge Supplies `nonce` and `signedAt`.
     * @return `connect.params` with a v3 device signature over [challenge]. Carries the credential: never log it.
     */
    fun connectParams(
        client: ClientInfo,
        auth: ConnectAuth,
        identity: DeviceIdentity,
        challenge: ConnectChallenge,
    ): JsonObject {
        val scopes = (auth as? ConnectAuth.DeviceToken)?.scopes?.takeIf { it.isNotEmpty() } ?: OPERATOR_SCOPES
        val signed = DeviceAuthPayload(
            deviceId = identity.deviceId,
            clientId = client.id,
            clientMode = client.mode,
            role = ROLE_OPERATOR,
            scopes = scopes,
            signedAtMs = challenge.ts,
            token = auth.signatureToken,
            nonce = challenge.nonce,
            platform = client.platform,
            deviceFamily = client.deviceFamily,
        ).v3()
        return buildJsonObject {
            put("minProtocol", GATEWAY_PROTOCOL_VERSION)
            put("maxProtocol", GATEWAY_PROTOCOL_VERSION)
            putJsonObject("client") {
                put("id", client.id)
                put("version", client.version)
                put("platform", client.platform)
                put("mode", client.mode)
                put("displayName", client.displayName)
                client.deviceFamily?.let { put("deviceFamily", it) }
            }
            put("role", ROLE_OPERATOR)
            putJsonArray("scopes") { scopes.forEach { add(it) } }
            putJsonArray("caps") {}
            putJsonArray("commands") {}
            putJsonObject("permissions") {}
            authJson(auth)?.let { put("auth", it) }
            put("locale", client.locale)
            put("userAgent", client.userAgent)
            putJsonObject("device") {
                put("id", identity.deviceId)
                put("publicKey", identity.publicKeyBase64Url)
                put("signature", identity.sign(signed))
                put("signedAt", challenge.ts)
                put("nonce", challenge.nonce)
            }
        }
    }

    private fun authJson(auth: ConnectAuth): JsonObject? = when (auth) {
        is ConnectAuth.SharedToken -> buildJsonObject { put("token", auth.token) }
        is ConnectAuth.DeviceToken -> buildJsonObject { put("deviceToken", auth.token) }
        ConnectAuth.None -> null
    }
}
