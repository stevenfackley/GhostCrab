package com.openclaw.ghostcrab.data.gateway

import com.openclaw.ghostcrab.domain.exception.GatewayProtocolException
import com.openclaw.ghostcrab.domain.model.AuthRequirement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Dashboard capability key: `config.get` + `config.patch` are available. */
const val CAPABILITY_CONFIG = "config"

/** Dashboard capability key: `models.list` is available. */
const val CAPABILITY_MODELS = "models"

/** Dashboard capability key: `skills.status` is available. */
const val CAPABILITY_SKILLS = "skills"

/**
 * `connect.challenge` payload.
 *
 * @param nonce Signed and echoed in `device.nonce`.
 * @param ts Server time in ms; used as `device.signedAt`.
 */
data class ConnectChallenge(val nonce: String, val ts: Long) {
    companion object {
        /**
         * @param endpoint For the error message.
         * @param payload The event payload.
         * @return The challenge.
         * @throws GatewayProtocolException unless it has a non-blank `nonce` and a non-negative integer `ts`
         *   (upstream treats anything else as invalid).
         */
        fun parse(endpoint: String, payload: JsonElement?): ConnectChallenge {
            val obj = payload as? JsonObject
            val nonce = obj?.stringOrNull("nonce")?.takeIf { it.isNotBlank() }
            val ts = (obj?.get("ts") as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it >= 0 }
            if (nonce == null || ts == null) {
                throw GatewayProtocolException(
                    endpoint,
                    "connect.challenge without a nonce and a non-negative integer ts",
                )
            }
            return ConnectChallenge(nonce, ts)
        }
    }
}

/**
 * The parts of `hello-ok` GhostCrab uses.
 *
 * @param protocol Negotiated protocol (always [GATEWAY_PROTOCOL_VERSION] once parsed).
 * @param serverVersion `server.version`, e.g. `2026.10.3`.
 * @param methods `features.methods`.
 * @param authMethod `auth.method`: `token`, `device-token`, `bootstrap-token`, `none`, ….
 * @param role `auth.role`.
 * @param scopes `auth.scopes`: the live authority of this socket.
 * @param deviceToken `auth.deviceToken`, when the gateway issued one. Sensitive: [toString] redacts it.
 * @param gatewayAuthMode `snapshot.authMode` (`token`, `password`, `none`, …); absent on a scope-less socket.
 * @param gatewayHost `host` of the `snapshot.presence` entry with `reason: "self"`.
 */
data class HelloOk(
    val protocol: Int,
    val serverVersion: String,
    val methods: Set<String>,
    val authMethod: String?,
    val role: String,
    val scopes: Set<String>,
    val deviceToken: String?,
    val gatewayAuthMode: String?,
    val gatewayHost: String?,
) {
    override fun toString(): String =
        "HelloOk(protocol=$protocol, serverVersion=$serverVersion, methods=${methods.size}, authMethod=$authMethod, " +
            "role=$role, scopes=$scopes, deviceToken=${deviceToken?.let { "[REDACTED]" }}, " +
            "gatewayAuthMode=$gatewayAuthMode, gatewayHost=$gatewayHost)"
}

/**
 * ADR-0007 replacement for ADR-002's probe: the gateway's auth mode from `hello-ok`.
 *
 * @param token The shared token used for this connect (decides when the hello says nothing).
 * @return [AuthRequirement.None] for `none`, else [AuthRequirement.Token].
 */
fun HelloOk.toAuthRequirement(token: String?): AuthRequirement = when (gatewayAuthMode ?: authMethod) {
    "none" -> AuthRequirement.None
    null -> if (token == null) AuthRequirement.None else AuthRequirement.Token
    else -> AuthRequirement.Token
}

/**
 * @param methods `hello-ok.features.methods`.
 * @return The GhostCrab capability keys these methods support, in a stable order.
 */
fun gatewayCapabilities(methods: Set<String>): List<String> = buildList {
    if ("config.get" in methods && "config.patch" in methods) add(CAPABILITY_CONFIG)
    if ("models.list" in methods) add(CAPABILITY_MODELS)
    if ("skills.status" in methods) add(CAPABILITY_SKILLS)
}

/**
 * @param endpoint For error messages.
 * @param payload The `connect` response payload.
 * @return The parsed hello.
 * @throws GatewayProtocolException if it is not a `hello-ok` or negotiated another protocol than
 *   [GATEWAY_PROTOCOL_VERSION].
 */
internal fun parseHelloOk(endpoint: String, payload: JsonElement): HelloOk {
    val obj = payload as? JsonObject
    val auth = obj?.get("auth") as? JsonObject
    if (obj == null || auth == null || obj.stringOrNull("type") != "hello-ok") {
        throw GatewayProtocolException(endpoint, "connect answered without a hello-ok payload")
    }
    val server = obj["server"] as? JsonObject
    val protocol = (obj["protocol"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.toInt()
    if (server == null || protocol != GATEWAY_PROTOCOL_VERSION) {
        throw GatewayProtocolException(
            endpoint,
            "gateway negotiated protocol $protocol; GhostCrab speaks $GATEWAY_PROTOCOL_VERSION",
        )
    }
    val snapshot = obj["snapshot"] as? JsonObject
    return HelloOk(
        protocol = GATEWAY_PROTOCOL_VERSION,
        serverVersion = server.stringOrNull("version") ?: "unknown",
        methods = (obj["features"] as? JsonObject)?.stringListOrEmpty("methods").orEmpty().toSet(),
        authMethod = auth.stringOrNull("method"),
        role = auth.stringOrNull("role") ?: GatewayHandshake.ROLE_OPERATOR,
        scopes = auth.stringListOrEmpty("scopes").toSet(),
        deviceToken = auth.stringOrNull("deviceToken"),
        gatewayAuthMode = snapshot?.stringOrNull("authMode"),
        gatewayHost = (snapshot?.get("presence") as? JsonArray)
            ?.firstNotNullOfOrNull { entry -> (entry as? JsonObject)?.takeIf { it.stringOrNull("reason") == "self" } }
            ?.stringOrNull("host"),
    )
}
