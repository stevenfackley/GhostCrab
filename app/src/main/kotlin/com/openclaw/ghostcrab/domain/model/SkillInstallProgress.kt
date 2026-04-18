package com.openclaw.ghostcrab.domain.model

sealed interface SkillInstallProgress {
    data object Idle : SkillInstallProgress
    data class Connecting(val target: String) : SkillInstallProgress
    data class Downloading(val pct: Int?) : SkillInstallProgress
    data class Verifying(val sha256Prefix: String) : SkillInstallProgress
    data class Applying(val step: String) : SkillInstallProgress
    data class Succeeded(val installed: InstalledSkill) : SkillInstallProgress
    data class Failed(val error: SkillInstallError) : SkillInstallProgress
}
