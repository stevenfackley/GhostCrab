package com.openclaw.ghostcrab.data.impl

import com.openclaw.ghostcrab.data.ws.GatewayWsClient
import com.openclaw.ghostcrab.domain.model.InstalledSkill
import com.openclaw.ghostcrab.domain.model.SkillInstallError
import com.openclaw.ghostcrab.domain.model.SkillInstallProgress
import com.openclaw.ghostcrab.domain.model.SkillSource
import com.openclaw.ghostcrab.domain.repository.InstalledSkillRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * WS-backed [InstalledSkillRepository]. Callers provide a factory so each op obtains
 * the live session for the currently connected gateway — mirrors the lazy lookup used
 * by [GatewayConnectionManagerImpl].
 *
 * @param wsFactory Suspending factory that returns the active [GatewayWsClient].
 */
@Suppress("TooGenericExceptionCaught")
class InstalledSkillRepositoryImpl(
    private val wsFactory: suspend () -> GatewayWsClient,
) : InstalledSkillRepository {

    private val installedFlow = MutableStateFlow<List<InstalledSkill>>(emptyList())

    /**
     * Hot [Flow] of the current installed-skill list. Starts empty; updated on
     * successful install/uninstall and after [refreshFromGateway].
     */
    override fun observeInstalled(): Flow<List<InstalledSkill>> = installedFlow.asStateFlow()

    /**
     * One-shot refresh from gateway `skills.list`.
     *
     * @return [Result] wrapping the refreshed list, or an exception on network failure.
     */
    override suspend fun refreshFromGateway(): Result<List<InstalledSkill>> = runCatching {
        val ws = wsFactory()
        val res = ws.request("skills.list", params = null).jsonObject
        val list = res["skills"]?.jsonArray.orEmpty().map { it.jsonObject.toInstalledSkill() }
        installedFlow.value = list
        list
    }

    /**
     * Starts an install via `skills.install`. The returned flow emits:
     * - [SkillInstallProgress.Connecting] immediately,
     * - zero or more intermediate progress notifications,
     * - a terminal [SkillInstallProgress.Succeeded] or [SkillInstallProgress.Failed].
     *
     * @param slug e.g. `"wanng-ide/auto-skill-hunter"`.
     * @param version `null` → latest.
     */
    @OptIn(FlowPreview::class)
    @Suppress("LongMethod")
    override fun install(slug: String, version: String?): Flow<SkillInstallProgress> = channelFlow {
        send(SkillInstallProgress.Connecting(slug))
        val ws = wsFactory()

        val notifJob = ws.notifications
            .filter { it.method == "skills.install.progress" }
            .onEach { n ->
                val p = n.params?.jsonObject ?: return@onEach
                val phase = p["phase"]?.jsonPrimitive?.contentOrNull
                val pct = p["pct"]?.jsonPrimitive?.intOrNull
                val progress: SkillInstallProgress? = when (phase) {
                    "downloading" -> SkillInstallProgress.Downloading(pct)
                    "verifying" -> SkillInstallProgress.Verifying(
                        sha256Prefix = p["sha256"]?.jsonPrimitive?.contentOrNull?.take(12) ?: ""
                    )
                    "applying" -> SkillInstallProgress.Applying(
                        step = p["step"]?.jsonPrimitive?.contentOrNull ?: "applying"
                    )
                    else -> null
                }
                if (progress != null) trySend(progress)
            }
            .launchIn(this)

        val params = buildJsonObject {
            put("source", JsonPrimitive("clawhub"))
            put("slug", JsonPrimitive(slug))
            if (version != null) put("version", JsonPrimitive(version))
            put("force", JsonPrimitive(false))
        }

        try {
            val result = ws.request("skills.install", params).jsonObject
            val skill = result.toInstalledSkill()
            installedFlow.value = (installedFlow.value.filter { it.slug != skill.slug } + skill)
            send(SkillInstallProgress.Succeeded(skill))
        } catch (e: GatewayWsClient.WsRpcException) {
            send(SkillInstallProgress.Failed(e.toInstallError()))
        } catch (e: Exception) {
            send(SkillInstallProgress.Failed(SkillInstallError.Unknown(e.message ?: "unknown")))
        } finally {
            notifJob.cancel()
        }
    }

    /**
     * Removes a previously-installed skill via `skills.uninstall`.
     *
     * @param slug The skill to remove.
     * @return [Result.success] on success, or a failure wrapping the exception.
     */
    override suspend fun uninstall(slug: String): Result<Unit> = runCatching {
        val ws = wsFactory()
        ws.request("skills.uninstall", buildJsonObject { put("slug", JsonPrimitive(slug)) })
        installedFlow.value = installedFlow.value.filter { it.slug != slug }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun JsonObject.toInstalledSkill(): InstalledSkill = InstalledSkill(
        slug = this["slug"]!!.jsonPrimitive.content,
        installedVersion = this["installed_version"]!!.jsonPrimitive.content,
        source = when (this["source"]?.jsonPrimitive?.contentOrNull) {
            "ClawHub", "clawhub" -> SkillSource.ClawHub
            "Local", "local" -> SkillSource.Local
            else -> SkillSource.Unknown
        },
        installedAt = this["installed_at"]?.jsonPrimitive?.long ?: 0L,
    )

    private fun GatewayWsClient.WsRpcException.toInstallError(): SkillInstallError = when (code) {
        -32003 -> SkillInstallError.Unauthorized(missingScope = "operator.admin")
        -32004 -> SkillInstallError.NotFound(slug = message)
        -32005 -> SkillInstallError.DependencyConflict(conflicts = message.split(","))
        -32006 -> SkillInstallError.VerificationFailed(expected = "", actual = message)
        else -> SkillInstallError.Protocol(rpcCode = code, message = message)
    }
}
