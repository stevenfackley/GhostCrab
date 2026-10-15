package com.openclaw.ghostcrab.domain.model

import kotlinx.serialization.Serializable

enum class SkillSource { ClawHub, Local, Unknown }

/**
 * A skill the gateway reports in `skills.status` (ADR-0007).
 *
 * @param slug `skillKey` (falls back to `name`).
 * @param installedVersion `clawhub.installedVersion` for ClawHub installs, else `version` when reported; empty
 *   otherwise (bundled skills have none).
 * @param source Where it came from.
 * @param installedAt Epoch seconds from `clawhub.installedAt` for ClawHub installs; 0 when unknown.
 * @param disabled Additive (v1.1, ADR-0007): `true` when `skills.entries.<key>.enabled` is false on the gateway.
 *   Defaults to `false`, so v1.0 constructions and serialized values stay valid.
 */
@Serializable
data class InstalledSkill(
    val slug: String,
    val installedVersion: String,
    val source: SkillSource,
    val installedAt: Long,
    val disabled: Boolean = false,
)
