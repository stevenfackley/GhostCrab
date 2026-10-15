package com.openclaw.ghostcrab.data.gateway

/**
 * Fields bound into the device signature of `connect` (upstream `buildDeviceAuthPayloadV3`).
 *
 * @param deviceId [DeviceIdentity.deviceId].
 * @param clientId `connect.params.client.id`.
 * @param clientMode `connect.params.client.mode`.
 * @param role `connect.params.role`.
 * @param scopes `connect.params.scopes`, in request order.
 * @param signedAtMs The challenge `ts` (also sent as `device.signedAt`).
 * @param token The credential sent in `auth` (shared or device token), or `null`. Sensitive.
 * @param nonce The challenge nonce (also sent as `device.nonce`).
 * @param platform `connect.params.client.platform`.
 * @param deviceFamily `connect.params.client.deviceFamily`, or `null`.
 */
data class DeviceAuthPayload(
    val deviceId: String,
    val clientId: String,
    val clientMode: String,
    val role: String,
    val scopes: List<String>,
    val signedAtMs: Long,
    val token: String?,
    val nonce: String,
    val platform: String,
    val deviceFamily: String?,
) {
    /** @return `v3|deviceId|clientId|mode|role|scopes|signedAt|token|nonce|platform|deviceFamily`. */
    fun v3(): String = listOf(
        "v3",
        deviceId,
        clientId,
        clientMode,
        role,
        scopes.joinToString(","),
        signedAtMs.toString(),
        token.orEmpty(),
        nonce,
        normalizeDeviceMetadata(platform),
        normalizeDeviceMetadata(deviceFamily),
    ).joinToString("|")

    override fun toString(): String =
        "DeviceAuthPayload(deviceId=$deviceId, clientId=$clientId, role=$role, scopes=$scopes, " +
            "signedAtMs=$signedAtMs, token=${token?.let { "[REDACTED]" }}, nonce=$nonce, platform=$platform)"
}

/** Upstream `normalizeDeviceMetadataForAuth`: trim, then lowercase ASCII `A`–`Z` only. */
private fun normalizeDeviceMetadata(value: String?): String =
    value?.trim()?.map { c -> if (c in 'A'..'Z') c + ('a' - 'A') else c }?.joinToString("").orEmpty()
