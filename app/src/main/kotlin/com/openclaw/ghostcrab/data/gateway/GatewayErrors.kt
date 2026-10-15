package com.openclaw.ghostcrab.data.gateway

import com.openclaw.ghostcrab.domain.exception.GatewayConnectAuthException
import com.openclaw.ghostcrab.domain.exception.GatewayException
import com.openclaw.ghostcrab.domain.exception.GatewayMethodUnsupportedException
import com.openclaw.ghostcrab.domain.exception.GatewayMissingScopeException
import com.openclaw.ghostcrab.domain.exception.GatewayPairingRequiredException
import com.openclaw.ghostcrab.domain.exception.GatewayRpcException

private const val MISSING_SCOPE_PREFIX = "missing scope: "
private const val UNKNOWN_METHOD_PREFIX = "unknown method:"

/**
 * Maps a failed response onto the typed [GatewayException] leaves. Branches on `code` and `details.code`
 * (upstream's contract); the legacy `missing scope: …` and `unknown method: …` messages are a fallback only.
 *
 * @param url The gateway WebSocket endpoint, for the message.
 * @param method The RPC method that failed.
 * @return The most specific exception for this error.
 */
internal fun ErrorShape.toGatewayException(url: String, method: String): GatewayException {
    val details = detailsObject
    val detail = detailCode
    return when {
        detail == "PAIRING_REQUIRED" -> GatewayPairingRequiredException(
            url = url,
            requestId = details?.stringOrNull("requestId"),
            reason = details?.stringOrNull("reason") ?: "not-paired",
            requestedScopes = details?.stringListOrEmpty("requestedScopes").orEmpty(),
        )
        detail != null && (detail.startsWith("AUTH_") || detail.startsWith("DEVICE_")) ->
            GatewayConnectAuthException(
                url = url,
                detailCode = detail,
                rpcMessage = message,
                recommendedNextStep = details?.stringOrNull("recommendedNextStep"),
                retryable = retryable == true,
                retryAfterMs = retryAfterMs,
            )
        code == "FORBIDDEN" && (detail == "MISSING_SCOPE" || message.startsWith(MISSING_SCOPE_PREFIX)) ->
            GatewayMissingScopeException(
                url = url,
                method = method,
                missingScope = details?.stringOrNull("missingScope") ?: message.removePrefix(MISSING_SCOPE_PREFIX),
            )
        code == "INVALID_REQUEST" && message.startsWith(UNKNOWN_METHOD_PREFIX) ->
            GatewayMethodUnsupportedException(url, method)
        else -> GatewayRpcException(
            url = url,
            method = method,
            code = code,
            rpcMessage = message,
            retryable = retryable == true,
            details = details,
            retryAfterMs = retryAfterMs,
        )
    }
}
