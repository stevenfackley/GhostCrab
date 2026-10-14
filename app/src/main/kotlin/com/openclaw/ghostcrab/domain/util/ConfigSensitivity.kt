package com.openclaw.ghostcrab.domain.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Placeholder written in place of any secret value. */
const val REDACTED_PLACEHOLDER = "[REDACTED]"

private val SECRET_KEY = Regex(
    "(?i)(token|secret|password|passwd|passphrase|pwd|authorization|api[_-]?key|private[_-]?key|credential)",
)

/** Bare `key`, a `_key` / `-key` suffix (any case) or a camelCase `Key` suffix such as `signingKey`. */
private val KEY_NAME = Regex("^(?i:key)$|(?i:[_-]key)$|Key$")

/** `scheme://userinfo@…` — a URL carrying credentials (`user:pass`, `:pass` or a bare token). */
private val CREDENTIAL_URL = Regex("(?i)[a-z][a-z0-9+.-]*://[^/?#\\s@]+@")

/**
 * `true` when [key] names a value that must never leave the device or be shown in clear.
 *
 * Case-insensitive substring match on `token`, `secret`, `password`, `passwd`, `passphrase`,
 * `pwd`, `authorization`, `api_key` / `api-key` / `apikey`, `private_key` and `credential`;
 * plus a bare `key`, any `_key` / `-key` suffix and a camelCase `Key` suffix. Lower-case words
 * that merely end in "key" (`monkey`, `hotkey`) do not match.
 *
 * @param key A single JSON object key (not a dotted path).
 * @return Whether the value stored under [key] is a secret.
 */
fun isSecretKey(key: String): Boolean = SECRET_KEY.containsMatchIn(key) || KEY_NAME.containsMatchIn(key)

/**
 * Deep copy of this element in which every value whose key satisfies [isSecretKey] is replaced by
 * [REDACTED_PLACEHOLDER], as is every string containing a URL with embedded credentials
 * (`scheme://user:pass@host`) whatever its key. Objects and arrays are traversed; other primitives
 * are returned unchanged. A secret key holding an object or array is replaced wholesale.
 *
 * @return The redacted element; the receiver is not modified.
 */
fun JsonElement.redactSecrets(): JsonElement = when (this) {
    is JsonObject -> JsonObject(
        mapValues { (key, value) ->
            if (isSecretKey(key)) JsonPrimitive(REDACTED_PLACEHOLDER) else value.redactSecrets()
        },
    )
    is JsonArray -> JsonArray(map { it.redactSecrets() })
    is JsonPrimitive ->
        if (isString && CREDENTIAL_URL.containsMatchIn(content)) JsonPrimitive(REDACTED_PLACEHOLDER) else this
}

private val SECURITY_PATH =
    Regex("(?i)(auth|token|secret|password|api[_-]?key|tls|ssl|cert|cors|cleartext|host|bind|allow)")

/**
 * `true` when changing the config value at [path] can weaken the gateway's security posture
 * (auth mode, credentials, TLS, bind address, CORS, cleartext or allow-lists). Over-matching is
 * intentional: a false positive costs one confirmation tap, a false negative can disable auth.
 *
 * @param path Dotted config path, e.g. `"gateway.auth.mode"`.
 * @return Whether applying a change at [path] needs explicit user confirmation.
 */
fun isSecurityAffectingConfigPath(path: String): Boolean = SECURITY_PATH.containsMatchIn(path)
