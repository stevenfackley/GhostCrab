package com.openclaw.ghostcrab.domain.repository

import com.openclaw.ghostcrab.domain.model.InstalledSkill
import com.openclaw.ghostcrab.domain.model.SkillInstallProgress
import kotlinx.coroutines.flow.Flow

/**
 * Frozen v1.1 contract — additive only. Lists, installs and enables/disables gateway skills over the session
 * socket (ADR-0007: `skills.status`, `skills.install`, `skills.update`).
 *
 * All methods assume an active [com.openclaw.ghostcrab.domain.model.GatewayConnection.Connected] state.
 */
interface InstalledSkillRepository {

    /**
     * Hot flow of the current installed-skill list. Updated by every `skills.status` read (including the re-reads
     * after an install and after [setSkillEnabled]). Starts empty until the first [refreshFromGateway] succeeds.
     */
    fun observeInstalled(): Flow<List<InstalledSkill>>

    /** One-shot refresh from gateway `skills.status` (ADR-0007). */
    suspend fun refreshFromGateway(): Result<List<InstalledSkill>>

    /**
     * Start an install. The returned flow emits a terminal
     * [SkillInstallProgress.Succeeded] or [SkillInstallProgress.Failed] and then completes.
     *
     * @param slug e.g. `"wanng-ide/auto-skill-hunter"`.
     * @param version `null` → latest.
     */
    fun install(slug: String, version: String? = null): Flow<SkillInstallProgress>

    /**
     * Remove a previously-installed skill.
     *
     * OpenClaw gateways have no skill-removal method (ADR-0007), so on them this always fails with
     * `GatewayMethodUnsupportedException` and sends nothing. Disable the skill with [setSkillEnabled] instead.
     *
     * @param slug The skill to remove.
     * @return Failure on OpenClaw gateways (see above).
     */
    suspend fun uninstall(slug: String): Result<Unit>

    /**
     * Enables or disables a skill (`skills.update {skillKey, enabled}`). OpenClaw has no removal method, so
     * this is what GhostCrab offers instead of [uninstall].
     *
     * Additive (v1.1): ADR-0005, ADR-0007.
     *
     * @param skillKey [com.openclaw.ghostcrab.domain.model.InstalledSkill.slug] as reported by `skills.status`
     *   (an `owner/slug` is reduced to `slug`).
     * @param enabled `false` to disable.
     * @return Success once the gateway accepted the change and the list was re-read; failure otherwise
     *   (`GatewayApiException` 404 for a key `skills.status` does not list, `GatewayMissingScopeException` without
     *   `operator.admin`).
     */
    suspend fun setSkillEnabled(skillKey: String, enabled: Boolean): Result<Unit>
}
