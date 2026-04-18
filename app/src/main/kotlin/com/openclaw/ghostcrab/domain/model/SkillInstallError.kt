package com.openclaw.ghostcrab.domain.model

sealed class SkillInstallError(val isRetryable: Boolean) {
    data class Unauthorized(val missingScope: String) : SkillInstallError(false)
    data class NotFound(val slug: String) : SkillInstallError(false)
    data class DependencyConflict(val conflicts: List<String>) : SkillInstallError(false)
    data class Network(val cause: String) : SkillInstallError(true)
    data class Protocol(val rpcCode: Int, val message: String) : SkillInstallError(false)
    data class VerificationFailed(val expected: String, val actual: String) : SkillInstallError(false)
    data class Unknown(val cause: String) : SkillInstallError(true)
}
