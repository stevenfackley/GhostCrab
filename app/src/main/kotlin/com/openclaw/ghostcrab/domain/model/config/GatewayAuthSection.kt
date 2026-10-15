package com.openclaw.ghostcrab.domain.model.config

import com.openclaw.ghostcrab.domain.util.REDACTED_PLACEHOLDER
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What the gateway sends in place of every secret in `config.get` (`raw`, `parsed`, `config`). */
const val OPENCLAW_REDACTED: String = "__OPENCLAW_REDACTED__"

/**
 * `gateway.auth.mode` values (upstream 2026.10.3, ADR-0007).
 *
 * @property wireName The value written to `openclaw.json`.
 */
@Serializable
enum class AuthMode(val wireName: String) {
    @SerialName("none")
    None("none"),

    @SerialName("token")
    Token("token"),

    @SerialName("password")
    Password("password"),

    @SerialName("trusted-proxy")
    TrustedProxy("trusted-proxy"),
}

/**
 * Typed view of `gateway.auth`.
 *
 * @param mode The configured mode, or `null` when unset (the gateway then infers it from the secrets present).
 * @param token The shared token; [OPENCLAW_REDACTED] when read from a gateway. Sensitive — never log.
 */
@Serializable
data class GatewayAuthSection(
    val mode: AuthMode? = null,
    val token: String? = null,
) {
    /** Redacts [token] so the section can be logged or dumped without leaking the secret. */
    override fun toString(): String =
        "GatewayAuthSection(mode=$mode, token=${token?.let { REDACTED_PLACEHOLDER }})"
}
