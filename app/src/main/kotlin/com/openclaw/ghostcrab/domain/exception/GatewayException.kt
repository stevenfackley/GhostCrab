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
) : GatewayException("Stored token for profile $profileId is no longer valid. Please reconnect.") {
    override val isRetryable: Boolean = false
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
