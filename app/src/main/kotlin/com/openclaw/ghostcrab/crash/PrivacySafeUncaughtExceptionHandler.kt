package com.openclaw.ghostcrab.crash

import android.util.Log

/**
 * Replaces the default uncaught exception handler to sanitize stack traces before logging.
 *
 * Strips:
 * - Bearer tokens: `Bearer <token>` → `Bearer [REDACTED]`
 * - URL credentials: `http://user:pass@host` → `http://[REDACTED]@host`
 * - Token-bearing fields: `token=abc`, `"token": "abc"`, `api_key=abc` → `token=[REDACTED]`
 * - IPv4 literals: `192.168.1.50` → `[IP]` (a gateway address identifies the user's network)
 *
 * The original (unsanitized) throwable is still forwarded to [delegate] so the process
 * terminates normally via the system handler.
 *
 * @param delegate The handler that was registered before this one (typically the system handler).
 */
class PrivacySafeUncaughtExceptionHandler(
    private val delegate: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        Log.e(TAG, sanitize(throwable.stackTraceToString()))
        // The *unsanitized* throwable is forwarded to delegate (the system handler) so
        // the process terminates normally. If a third-party crash SDK (Crashlytics, Sentry,
        // ACRA) is ever added, wrap throwable before passing it here — exception messages
        // from GatewayAuthException / GatewayUnreachableException can contain full URLs.
        delegate?.uncaughtException(thread, throwable)
    }

    companion object {
        private const val TAG = "GhostCrab/Crash"

        private val BEARER_REGEX = Regex("""Bearer\s+\S+""", RegexOption.IGNORE_CASE)
        private val CREDENTIALS_URL_REGEX = Regex("""(https?://)[^@\s]+@""", RegexOption.IGNORE_CASE)

        /** `token=…`, `"token": "…"`, `access_token=…`, `api_key=…` in query, JSON, or k=v log form. */
        private val TOKEN_FIELD_REGEX = Regex(
            """((?:access_|auth_|bearer_)?token|api[_-]?key|secret|password)(["']?\s*[:=]\s*["']?)[^\s"'&,;}\]]+""",
            RegexOption.IGNORE_CASE,
        )

        /** IPv4 dotted-quad. IPv6 is left alone — it collides with too much innocent hex. */
        private val IPV4_REGEX = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")

        /**
         * Returns [text] with bearer tokens, URL credentials, token-bearing fields and IPv4
         * addresses redacted. Internal so unit tests can call it directly.
         */
        internal fun sanitize(text: String): String = text
            .replace(BEARER_REGEX, "Bearer [REDACTED]")
            .replace(CREDENTIALS_URL_REGEX, "\$1[REDACTED]@")
            .replace(TOKEN_FIELD_REGEX, "\$1\$2[REDACTED]")
            .replace(IPV4_REGEX, "[IP]")
    }
}
