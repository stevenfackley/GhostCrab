package com.openclaw.ghostcrab.domain.model

sealed class SkillInstallError(val isRetryable: Boolean) {
    data class Unauthorized(val missingScope: String) : SkillInstallError(false)
    data class NotFound(val slug: String) : SkillInstallError(false)
    data class DependencyConflict(val conflicts: List<String>) : SkillInstallError(false)
    data class Network(val cause: Throwable) : SkillInstallError(true)
    /** The gateway rejected the install with string [code] (e.g. `UNAVAILABLE`) and [message] (ADR-0007). */
    data class Protocol(val code: String, val message: String) : SkillInstallError(false)
    data class VerificationFailed(val expected: String, val actual: String) : SkillInstallError(false)
    /** The gateway answered with JSON of an unexpected shape. Not retryable. */
    data class MalformedResponse(val detail: String) : SkillInstallError(false)
    /** No active gateway session. Not retryable as-is: reconnect first. */
    data object NotConnected : SkillInstallError(false)
    data class Unknown(val cause: Throwable) : SkillInstallError(true)
}
