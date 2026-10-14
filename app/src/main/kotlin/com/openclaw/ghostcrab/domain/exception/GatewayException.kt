package com.openclaw.ghostcrab.domain.exception

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
 * The gateway does not have the AI recommendation skill installed.
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
 * @param field The config key that failed validation (e.g. `"gateway.http.port"`).
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
