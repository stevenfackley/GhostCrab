package com.openclaw.ghostcrab.domain.exception

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Sealed hierarchy for all errors originating from gateway communication or local profile state.
 *
 * Every leaf carries [isRetryable] so callers can implement retry logic without inspecting
 * the concrete type.
 */
sealed class GatewayException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /** Whether the operation that produced this exception is safe to retry. */
    abstract val isRetryable: Boolean
}

/**
 * The gateway host is not reachable (DNS failure, refused connection, network error).
 *
 * @param url The URL that could not be reached.
 */
class GatewayUnreachableException(
    val url: String,
    cause: Throwable? = null,
) : GatewayException("Gateway unreachable at $url", cause) {
    override val isRetryable: Boolean = true
}

/**
 * The gateway rejected the request due to missing or invalid credentials.
 *
 * @param url The URL that returned the auth error.
 * @param statusCode HTTP status code (401 or 403).
 */
class GatewayAuthException(
    val url: String,
    val statusCode: Int,
) : GatewayException("Authentication failed at $url (HTTP $statusCode)") {
    override val isRetryable: Boolean = false
}

/**
 * The gateway returned an unexpected HTTP error.
 *
 * @param url The URL that returned the error.
 * @param statusCode HTTP status code.
 * @param body Response body excerpt, if available.
 */
class GatewayApiException(
    val url: String,
    val statusCode: Int,
    val body: String? = null,
) : GatewayException("Gateway API error at $url: HTTP $statusCode${body?.let { " — $it" } ?: ""}") {
    override val isRetryable: Boolean = false
}

/**
 * The request to the gateway timed out.
 *
 * @param url The URL that timed out.
 */
class GatewayTimeoutException(
    val url: String,
    cause: Throwable? = null,
) : GatewayException("Gateway request timed out at $url", cause) {
    override val isRetryable: Boolean = true
}

/**
 * TLS handshake failed (e.g. self-signed certificate not trusted).
 *
 * @param url The URL that failed TLS negotiation.
 */
class GatewayTlsException(
    val url: String,
    cause: Throwable? = null,
) : GatewayException("TLS handshake failed for $url", cause) {
    override val isRetryable: Boolean = false
}

/**
 * A saved profile's encrypted token could not be decrypted (e.g. after a factory reset).
 * The encrypted entry has been cleared; the user must re-authenticate.
 *
 * @param profileId The profile whose token is now invalid.
 */
class ProfileNeedsReauthException(
    val profileId: String,
    cause: Throwable? = null,
) : GatewayException("Stored token for profile $profileId is no longer valid. Please reconnect.", cause) {
    override val isRetryable: Boolean = false
}

/**
 * The saved-profiles store holds data this build cannot decode (corruption or a newer schema).
 * Writes are refused so that data is never overwritten.
 *
 * @param cause The decoding failure.
 */
class ProfileStoreCorruptedException(cause: Throwable) :
    GatewayException("Saved gateway profiles could not be read; refusing to overwrite them.", cause) {
    override val isRetryable: Boolean = false
}

/**
 * Clearing all saved gateways removed the profile list but could not wipe the encrypted token store,
 * so saved tokens may remain on the device.
 *
 * @param cause The failure from the encrypted preferences.
 */
class TokenWipeFailedException(cause: Throwable) :
    GatewayException("Saved gateways were removed but stored tokens could not be wiped.", cause) {
    override val isRetryable: Boolean = true
}

/**
 * The gateway offers no AI recommendations. OpenClaw has no recommendation API (ADR-0007), so this is thrown for
 * every gateway until a `chat.send`-based design gets its own ADR. Not retryable.
 *
 * @param url The gateway URL.
 */
class AIServiceUnavailableException(
    val url: String,
) : GatewayException("AI recommendation skill not available on gateway at $url") {
    override val isRetryable: Boolean = false
}

/**
 * The AI recommendation service rate-limited the request.
 *
 * @param url The gateway URL.
 */
class AIQuotaExceededException(
    val url: String,
) : GatewayException("AI recommendation quota exceeded at $url") {
    override val isRetryable: Boolean = false
}

/**
 * A config value failed client-side or server-side validation.
 *
 * @param field The config key that failed validation (e.g. `"gateway.port"`).
 * @param reason Human-readable reason for the failure.
 */
class ConfigValidationException(
    val field: String,
    val reason: String,
) : GatewayException("Invalid value for '$field': $reason") {
    override val isRetryable: Boolean = false
}

/**
 * A cleartext (`http://`) request to a public IP literal was refused by the app's own policy before
 * it left the device. Fix: use HTTPS, or enable Settings → Connections → "Allow HTTP to public IPs".
 *
 * @param url The URL that was blocked.
 * @param host The public IP literal that triggered the block.
 */
class CleartextBlockedException(
    val url: String,
    val host: String,
    cause: Throwable? = null,
) : GatewayException("${policyMessage(host)} URL: $url", cause) {
    override val isRetryable: Boolean = false

    companion object {
        /**
         * The policy text, shared with the data-layer interceptor's `IOException` so the advice is
         * worded identically on both and is printed only once when the cause chain is rendered.
         *
         * @param host The public IP literal that was blocked.
         * @return A sentence naming the host and the exact Settings toggle that lifts the block.
         */
        fun policyMessage(host: String): String =
            "Cleartext HTTP to public IP $host blocked by app policy. " +
                "Use HTTPS or enable Settings → Connections → Allow HTTP to public IPs."
    }
}

/**
 * The gateway answered, but not in a way this client understands (undecodable body, refused
 * WebSocket upgrade), or the request could not even be built. Not a connectivity problem.
 *
 * @param url The URL involved.
 * @param detail What went wrong, for display.
 */
class GatewayProtocolException(
    val url: String,
    val detail: String,
    cause: Throwable? = null,
) : GatewayException("Unexpected gateway response at $url: $detail", cause) {
    override val isRetryable: Boolean = false
}

/**
 * An operation needed the active gateway session but none exists (never connected, or the
 * connection dropped mid-flight). Not retryable as-is: the user must reconnect first.
 */
class GatewayNotConnectedException :
    GatewayException("No active gateway connection. Reconnect and try again.") {
    override val isRetryable: Boolean = false
}

/**
 * A gateway RPC answered `ok:false` with a code this client has no more specific type for (ADR-0007).
 *
 * @param url The gateway WebSocket endpoint.
 * @param method The RPC method.
 * @param code Top-level error code (e.g. `UNAVAILABLE`, `INVALID_REQUEST`).
 * @param rpcMessage The server's message, verbatim.
 * @param retryable The server's `retryable` hint.
 * @param details `error.details` when it is an object (e.g. `config.patch` validation `issues[]`), else `null`.
 * @param retryAfterMs The server's back-off hint, if any.
 */
@Suppress("LongParameterList") // The request context plus upstream's ErrorShape, field for field.
class GatewayRpcException(
    val url: String,
    val method: String,
    val code: String,
    val rpcMessage: String,
    retryable: Boolean,
    val details: JsonObject? = null,
    val retryAfterMs: Long? = null,
) : GatewayException(
    "Gateway RPC $method failed at $url: $code${detailCodeOf(details)?.let { "/$it" } ?: ""} — $rpcMessage",
) {
    override val isRetryable: Boolean = retryable

    /** `error.details.code`, when the server sent one. */
    val detailCode: String? = detailCodeOf(details)
}

/** `details.code` when it is a JSON string, else `null`. */
private fun detailCodeOf(details: JsonObject?): String? =
    (details?.get("code") as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * The gateway does not implement [method] (`INVALID_REQUEST unknown method: …`). The feature is unsupported
 * on this gateway; retrying cannot help.
 *
 * @param url The gateway WebSocket endpoint.
 * @param method The RPC method the gateway does not know.
 */
class GatewayMethodUnsupportedException(
    val url: String,
    val method: String,
) : GatewayException("Gateway at $url does not support '$method' (INVALID_REQUEST unknown method)") {
    override val isRetryable: Boolean = false
}

/**
 * The connection lacks the operator scope [method] needs (`FORBIDDEN` / `MISSING_SCOPE`). Approve this device
 * with that scope on the gateway host.
 *
 * @param url The gateway WebSocket endpoint.
 * @param method The refused RPC method.
 * @param missingScope The scope the gateway reported, e.g. `operator.admin`.
 */
class GatewayMissingScopeException(
    val url: String,
    val method: String,
    val missingScope: String,
) : GatewayException("Gateway at $url refused '$method': missing scope $missingScope (FORBIDDEN/MISSING_SCOPE)") {
    override val isRetryable: Boolean = false
}

/**
 * The gateway knows this device but its owner has not approved it yet (`NOT_PAIRED` / `PAIRING_REQUIRED`).
 * Retryable: the same connect succeeds once `openclaw devices approve <requestId>` ran on the gateway host.
 *
 * @param url The gateway WebSocket endpoint.
 * @param requestId The pending pairing request, when the gateway sent one.
 * @param reason `not-paired`, `scope-upgrade`, `role-upgrade` or `metadata-upgrade`.
 * @param requestedScopes The scopes this device asked for.
 */
class GatewayPairingRequiredException(
    val url: String,
    val requestId: String?,
    val reason: String,
    val requestedScopes: List<String>,
) : GatewayException(
    "Gateway at $url needs this device approved ($reason). On the gateway host run: " +
        (requestId?.let { "openclaw devices approve $it" } ?: "openclaw devices list"),
) {
    override val isRetryable: Boolean = true
}

/**
 * The gateway rejected the `connect` handshake's credential or device proof (`error.details.code` starting
 * with `AUTH_` or `DEVICE_`, e.g. `AUTH_TOKEN_MISMATCH`, `AUTH_TOKEN_MISSING`, `DEVICE_IDENTITY_REQUIRED`).
 *
 * @param url The gateway WebSocket endpoint.
 * @param detailCode The structured code.
 * @param rpcMessage The server's message, verbatim.
 * @param recommendedNextStep Upstream recovery hint (`update_auth_credentials`, `wait_then_retry`, …), if any.
 * @param retryable The server's `retryable` hint. The rejection is retryable when this is `true` or
 *   [recommendedNextStep] is `wait_then_retry`; otherwise the credential has to change first.
 * @param retryAfterMs The server's back-off hint, if any.
 */
class GatewayConnectAuthException(
    val url: String,
    val detailCode: String,
    val rpcMessage: String,
    val recommendedNextStep: String?,
    retryable: Boolean = false,
    val retryAfterMs: Long? = null,
) : GatewayException("Gateway at $url rejected the connect handshake: $detailCode — $rpcMessage") {
    override val isRetryable: Boolean = retryable || recommendedNextStep == NEXT_STEP_WAIT_THEN_RETRY

    private companion object {
        const val NEXT_STEP_WAIT_THEN_RETRY = "wait_then_retry"
    }
}

/**
 * The gateway closed the control-plane socket with a policy-violation close (WebSocket 1008) and no response
 * explaining it, e.g. a socket that never sent `connect` or a frame the gateway refused. Not retryable as-is:
 * the same frames would be refused again.
 *
 * @param url The gateway WebSocket endpoint.
 * @param closeCode The WebSocket close code.
 * @param closeReason The close reason the gateway sent; may be empty.
 */
class GatewayConnectionClosedException(
    val url: String,
    val closeCode: Int,
    val closeReason: String,
) : GatewayException(
    "Gateway at $url closed the connection: WebSocket $closeCode" +
        (closeReason.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""),
) {
    override val isRetryable: Boolean = false
}

/**
 * This phone's encrypted storage (Android Keystore + EncryptedSharedPreferences) refused to read or write the
 * gateway device identity or a device token (ADR-0007). Retryable: Keystore errors are often transient (for
 * example right after boot); a lasting one needs the app's storage cleared.
 *
 * @param operation What could not be done, e.g. `save the device token for http://gw.local:18789`. Never holds a
 *   secret.
 * @param cause The Keystore or preferences failure.
 */
class DeviceCredentialStorageException(
    val operation: String,
    cause: Throwable,
) : GatewayException("Could not $operation in this phone's encrypted storage.", cause) {
    override val isRetryable: Boolean = true
}
