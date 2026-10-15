package com.openclaw.ghostcrab.data.impl

import com.openclaw.ghostcrab.data.gateway.GatewayRpcClient
import com.openclaw.ghostcrab.data.gateway.stringOrNull
import com.openclaw.ghostcrab.domain.exception.GatewayApiException
import com.openclaw.ghostcrab.domain.exception.GatewayException
import com.openclaw.ghostcrab.domain.exception.GatewayMethodUnsupportedException
import com.openclaw.ghostcrab.domain.exception.GatewayMissingScopeException
import com.openclaw.ghostcrab.domain.exception.GatewayNotConnectedException
import com.openclaw.ghostcrab.domain.exception.GatewayProtocolException
import com.openclaw.ghostcrab.domain.exception.GatewayRpcException
import com.openclaw.ghostcrab.domain.model.InstalledSkill
import com.openclaw.ghostcrab.domain.model.SkillInstallError
import com.openclaw.ghostcrab.domain.model.SkillInstallProgress
import com.openclaw.ghostcrab.domain.model.SkillSource
import com.openclaw.ghostcrab.domain.repository.InstalledSkillRepository
import com.openclaw.ghostcrab.domain.util.suspendRunCatching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

private const val METHOD_STATUS = "skills.status"
private const val METHOD_INSTALL = "skills.install"
private const val METHOD_UNINSTALL = "skills.uninstall"
private const val METHOD_UPDATE = "skills.update"
private const val EVENT_CONFIG_CHANGED = "config.changed"
private const val STATUS_NOT_FOUND = 404
private const val NOT_CONNECTED_URL = "<no active connection>"
private const val MILLIS_PER_SECOND = 1_000L

/** Sent as `skills.install.timeoutMs`: the gateway downloads and verifies before it answers. */
private const val INSTALL_TIMEOUT_MS = 120_000L
private const val INSTALL_TIMEOUT_MARGIN_MS = 10_000L

/** `config.changed` follows a write within ~80 ms; without it the list is re-read anyway after this long. */
private const val CONFIG_CHANGED_WAIT_MS = 2_000L

/**
 * [InstalledSkillRepository] over the live session socket (ADR-0007): `skills.status` lists, `skills.install`
 * installs (one response, no progress events). OpenClaw has no removal method, so [uninstall] fails with
 * [GatewayMethodUnsupportedException].
 *
 * @param session Returns the live session's socket; throws [GatewayNotConnectedException] while disconnected.
 */
class InstalledSkillRepositoryImpl(
    private val session: () -> GatewayRpcClient,
) : InstalledSkillRepository {

    private val installedFlow = MutableStateFlow<List<InstalledSkill>>(emptyList())

    /** Hot list; starts empty, updated by every `skills.status` read and by a successful install. */
    override fun observeInstalled(): Flow<List<InstalledSkill>> = installedFlow.asStateFlow()

    /**
     * One-shot `skills.status`.
     *
     * @return The listed skills, or a failure: [GatewayProtocolException] for a payload without a `skills` array or
     *   an entry without a string `skillKey` / `name`, [GatewayNotConnectedException] while disconnected, or the
     *   typed RPC error.
     */
    override suspend fun refreshFromGateway(): Result<List<InstalledSkill>> =
        suspendRunCatching { fetchStatus(session()) }

    /**
     * Emits [SkillInstallProgress.Connecting], [SkillInstallProgress.Applying] (`skills.install`), then
     * [SkillInstallProgress.Succeeded] or [SkillInstallProgress.Failed], and completes. Never throws to the
     * collector except for cancellation.
     *
     * @param slug ClawHub reference, e.g. `@steipete/weather`.
     * @param version `null` → latest.
     */
    override fun install(slug: String, version: String?): Flow<SkillInstallProgress> = flow {
        emit(SkillInstallProgress.Connecting(slug))
        val rpc = try {
            session()
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: GatewayNotConnectedException) {
            // Not retryable as-is: the user must reconnect first, so no Retry button.
            emit(SkillInstallProgress.Failed(SkillInstallError.NotConnected))
            return@flow
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            emit(SkillInstallProgress.Failed(SkillInstallError.Network(e)))
            return@flow
        }
        emit(SkillInstallProgress.Applying(step = METHOD_INSTALL))
        emit(installOutcome(rpc, slug, version))
    }

    /** OpenClaw has no skill-removal RPC (QA §3); nothing is sent. See [setSkillEnabled]. */
    override suspend fun uninstall(slug: String): Result<Unit> =
        Result.failure(GatewayMethodUnsupportedException(endpointOrPlaceholder(), METHOD_UNINSTALL))

    /**
     * `skills.update {skillKey, enabled}`, then a `skills.status` re-read once the gateway emits `config.changed`
     * (the list is stale until then, and the event can precede the response; after [CONFIG_CHANGED_WAIT_MS]
     * without it the list is re-read anyway). The gateway answers `ok:true` even for unknown keys, so the key is
     * first checked against a fresh `skills.status` of this socket: a cached list could come from another gateway.
     *
     * @param skillKey The skill's key; an `owner/slug` is reduced to `slug`.
     * @param enabled `false` to disable.
     * @return Success after the re-read; failure with a 404-style
     *   [com.openclaw.ghostcrab.domain.exception.GatewayApiException] for an unlisted key, or the typed RPC error.
     */
    override suspend fun setSkillEnabled(skillKey: String, enabled: Boolean): Result<Unit> = suspendRunCatching {
        val rpc = session()
        val key = skillKey.substringAfterLast('/')
        if (fetchStatus(rpc).none { it.slug == key }) {
            throw GatewayApiException(rpc.endpoint, STATUS_NOT_FOUND, "skill $key is not listed by skills.status")
        }
        coroutineScope {
            // Subscribed before the request goes out, so an early config.changed is not missed.
            val changed = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(CONFIG_CHANGED_WAIT_MS) {
                    // firstOrNull: a socket that ends completes the flow; the re-read below then fails typed.
                    rpc.events.firstOrNull { it.event == EVENT_CONFIG_CHANGED }
                }
            }
            val params = buildJsonObject {
                put("skillKey", key)
                put("enabled", enabled)
            }
            rpc.request(METHOD_UPDATE, params)
            changed.await()
        }
        fetchStatus(rpc)
        Unit
    }

    private suspend fun installOutcome(rpc: GatewayRpcClient, slug: String, version: String?): SkillInstallProgress =
        try {
            val timeoutMs = INSTALL_TIMEOUT_MS + INSTALL_TIMEOUT_MARGIN_MS
            val result = rpc.request(METHOD_INSTALL, installParams(slug, version), timeoutMs) as? JsonObject
            SkillInstallProgress.Succeeded(installedSkillAfter(rpc, slug, result))
        } catch (e: CancellationException) {
            throw e
        } catch (e: GatewayException) {
            SkillInstallProgress.Failed(e.toInstallError(slug))
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            SkillInstallProgress.Failed(SkillInstallError.Unknown(e))
        }

    private suspend fun fetchStatus(rpc: GatewayRpcClient): List<InstalledSkill> {
        val skills = (rpc.request(METHOD_STATUS) as? JsonObject)?.get("skills") as? JsonArray
            ?: throw GatewayProtocolException(rpc.endpoint, "skills.status without a skills array")
        return skills.map { it.toInstalledSkill(rpc.endpoint) }.also { installedFlow.value = it }
    }

    /**
     * The install already succeeded; this only finds its entry. `skills.install` answers with the bare key and the
     * installed version (`{ok, message, slug, version, targetDir, …}`). A failed or incomplete refresh falls back
     * to a synthetic entry rather than reporting a successful install as failed.
     */
    private suspend fun installedSkillAfter(
        rpc: GatewayRpcClient,
        requested: String,
        result: JsonObject?,
    ): InstalledSkill {
        val key = result?.stringOrNull("slug") ?: requested.substringAfterLast('/')
        // A refresh failure is not the install's failure: the synthetic entry below stands in for the list.
        val refreshed = suspendRunCatching { fetchStatus(rpc) }.getOrNull()
        refreshed?.firstOrNull { it.slug == key }?.let { return it }
        val synthetic =
            InstalledSkill(key, result?.stringOrNull("version").orEmpty(), SkillSource.ClawHub, installedAt = 0L)
        if (refreshed == null) installedFlow.value = installedFlow.value.filterNot { it.slug == key } + synthetic
        return synthetic
    }

    private fun endpointOrPlaceholder(): String = try {
        session().endpoint
    } catch (ignored: GatewayNotConnectedException) {
        NOT_CONNECTED_URL
    }
}

private fun installParams(slug: String, version: String?) = buildJsonObject {
    put("source", "clawhub")
    put("slug", slug)
    version?.let { put("version", it) }
    put("force", false)
    put("timeoutMs", INSTALL_TIMEOUT_MS)
}

/** Strict: an entry that is not an object or has no string key fails the whole list instead of vanishing. */
private fun JsonElement.toInstalledSkill(url: String): InstalledSkill {
    val entry = this as? JsonObject ?: throw GatewayProtocolException(url, "skills.status entry is not an object")
    val key = entry.stringOrNull("skillKey") ?: entry.stringOrNull("name")
        ?: throw GatewayProtocolException(url, "skills.status entry without a skillKey or name")
    val clawhub = entry["clawhub"] as? JsonObject
    val installedAtMs = (clawhub?.get("installedAt") as? JsonPrimitive)?.longOrNull ?: 0L
    return InstalledSkill(
        slug = key,
        installedVersion = clawhub?.stringOrNull("installedVersion") ?: entry.stringOrNull("version").orEmpty(),
        source = skillSourceOf(entry.stringOrNull("source"), entry.flag("bundled"), clawhub = clawhub != null),
        installedAt = installedAtMs / MILLIS_PER_SECOND,
        disabled = entry.flag("disabled"),
    )
}

/** `true` only for the JSON boolean `true` (never the string `"true"`). */
private fun JsonObject.flag(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true

/**
 * `skills.status` entry → [SkillSource]. Verified against 2026.10.3: a ClawHub install reports
 * `source: "openclaw-workspace"` plus a `clawhub {…}` object; bundled and plugin skills (`openclaw-bundled`,
 * `openclaw-extra`) and hand-made workspace skills have no `clawhub` object and are local.
 *
 * @param source The reported source.
 * @param bundled The reported `bundled` flag.
 * @param clawhub Whether the entry carries a `clawhub` object.
 * @return The mapped source.
 */
internal fun skillSourceOf(source: String?, bundled: Boolean, clawhub: Boolean): SkillSource = when {
    clawhub -> SkillSource.ClawHub
    source == null -> SkillSource.Unknown
    bundled || source.startsWith("openclaw-") -> SkillSource.Local
    else -> SkillSource.Unknown
}

private fun GatewayException.toInstallError(slug: String): SkillInstallError = when (this) {
    is GatewayMissingScopeException -> SkillInstallError.Unauthorized(missingScope)
    is GatewayRpcException ->
        if (code == "UNAVAILABLE" && rpcMessage.contains("not found on ClawHub")) {
            SkillInstallError.NotFound(slug)
        } else {
            SkillInstallError.Protocol(code, rpcMessage)
        }
    is GatewayMethodUnsupportedException -> SkillInstallError.Protocol("INVALID_REQUEST", message.orEmpty())
    is GatewayProtocolException -> SkillInstallError.MalformedResponse(detail)
    is GatewayNotConnectedException -> SkillInstallError.NotConnected
    // Timeout or socket closed mid-install: retryable transport failure.
    else -> SkillInstallError.Network(this)
}
