package com.openclaw.ghostcrab.domain.model

import kotlinx.serialization.Serializable

enum class SkillSource { ClawHub, Local, Unknown }

@Serializable
data class InstalledSkill(
    val slug: String,
    val installedVersion: String,
    val source: SkillSource,
    val installedAt: Long,
)
