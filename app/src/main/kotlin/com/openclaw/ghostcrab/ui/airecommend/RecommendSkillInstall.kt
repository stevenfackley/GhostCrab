package com.openclaw.ghostcrab.ui.airecommend

import com.openclaw.ghostcrab.domain.repository.InstalledSkillRepository

/**
 * A gateway skill that serves AI recommendations, installable in-app from ClawHub. No such skill exists today:
 * OpenClaw has no recommendation API and a `chat.send`-based design needs its own ADR (ADR-0007), so the app wires
 * none and [AIRecommendationViewModel] offers no install.
 *
 * @param slug ClawHub slug of the skill.
 * @param installer The in-app skill installer.
 */
class RecommendSkillInstall(
    val slug: String,
    val installer: InstalledSkillRepository,
)
