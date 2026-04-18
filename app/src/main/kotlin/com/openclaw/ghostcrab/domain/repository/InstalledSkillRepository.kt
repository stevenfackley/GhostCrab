package com.openclaw.ghostcrab.domain.repository

import com.openclaw.ghostcrab.domain.model.InstalledSkill
import com.openclaw.ghostcrab.domain.model.SkillInstallProgress
import kotlinx.coroutines.flow.Flow

/**
 * Frozen v1.1 contract — additive only. Install + list + uninstall via gateway WebSocket.
 *
 * All methods assume an active [com.openclaw.ghostcrab.domain.model.GatewayConnection.Connected]
 * state. Callers get a `Flow` so the UI can stream progress notifications live.
 */
interface InstalledSkillRepository {

    /**
     * Hot flow of the current installed-skill list. Refreshed when the gateway reports
     * install/uninstall success. Starts empty until the first [refreshFromGateway] succeeds.
     */
    fun observeInstalled(): Flow<List<InstalledSkill>>

    /** One-shot refresh from gateway `skills.list`. */
    suspend fun refreshFromGateway(): Result<List<InstalledSkill>>

    /**
     * Start an install. The returned flow emits a terminal
     * [SkillInstallProgress.Succeeded] or [SkillInstallProgress.Failed] and then completes.
     *
     * @param slug e.g. `"wanng-ide/auto-skill-hunter"`.
     * @param version `null` → latest.
     */
    fun install(slug: String, version: String? = null): Flow<SkillInstallProgress>

    /** Remove a previously-installed skill. */
    suspend fun uninstall(slug: String): Result<Unit>
}
