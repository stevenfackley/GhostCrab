package com.openclaw.ghostcrab.data.impl

import com.openclaw.ghostcrab.data.gateway.GatewayRpcClient
import com.openclaw.ghostcrab.data.gateway.stringOrNull
import com.openclaw.ghostcrab.domain.exception.GatewayProtocolException
import com.openclaw.ghostcrab.domain.exception.GatewayRpcException
import com.openclaw.ghostcrab.domain.model.OpenClawConfig
import com.openclaw.ghostcrab.domain.repository.ConfigRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private const val METHOD_GET = "config.get"
private const val METHOD_PATCH = "config.patch"

/**
 * [ConfigRepository] over the live session socket (ADR-0007). [getConfig] reads `config.get` (`parsed` sections,
 * `hash` as the ETag); [updateConfig] sends `config.patch {raw: {"<section>": value}, baseHash}`.
 *
 * The cached hash is bound to the socket that produced it, so a reconnect never sends a stale one; without a
 * cached hash a write reads `config.get` first. The hash a write returns is the next `baseHash`; a write answer
 * without one clears the cache (the pre-write hash is stale by definition). Reads and writes run one at a time
 * under [mutex] held across the request, so the cache always comes from the request that completed last: a slow
 * read overlapping a write cannot replace the write's newer hash with its own.
 *
 * @param session Returns the live session's socket; throws `GatewayNotConnectedException` while disconnected.
 */
class ConfigRepositoryImpl(
    private val session: () -> GatewayRpcClient,
) : ConfigRepository {

    private class CachedHash(val rpc: GatewayRpcClient, val hash: String?)

    private val mutex = Mutex()

    /** Guarded by [mutex]. */
    private var lastHash: CachedHash? = null

    /**
     * @return The authored config (`parsed`, secrets redacted by the gateway) and its `hash`.
     * @throws GatewayProtocolException if the payload is not an object.
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayMissingScopeException without `operator.admin`.
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayNotConnectedException if no session is active.
     */
    override suspend fun getConfig(): OpenClawConfig = mutex.withLock { fetch(session()) }

    /**
     * @param section Top-level key, e.g. `gateway`.
     * @param value Merge-patch value for that section.
     * @throws com.openclaw.ghostcrab.domain.exception.ConfigValidationException when the gateway's schema check
     *   rejects the value (`details.issues[0]`: its `path`, e.g. `gateway.port`, and `message`).
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayApiException 412 on a stale or missing hash, 422 on
     *   any other rejected patch (the body is the server text, e.g. a JSON5 syntax error).
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayMissingScopeException without `operator.admin`.
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayNotConnectedException if no session is active.
     */
    override suspend fun updateConfig(section: String, value: JsonElement): Unit = mutex.withLock {
        val rpc = session()
        val baseHash = lastHash?.takeIf { it.rpc === rpc }?.hash ?: fetch(rpc).etag
        val params = configPatchParams(JsonObject(mapOf(section to value)), baseHash)
        val result = try {
            rpc.request(METHOD_PATCH, params, CONFIG_PATCH_TIMEOUT_MS)
        } catch (e: GatewayRpcException) {
            throw e.toConfigWriteException()
        }
        // 2026.10.3 answers {ok, path, hash, config, changedPaths, sentinel}; without a hash the next write re-reads.
        lastHash = CachedHash(rpc, (result as? JsonObject)?.stringOrNull("hash"))
    }

    private suspend fun fetch(rpc: GatewayRpcClient): OpenClawConfig {
        val payload = rpc.request(METHOD_GET) as? JsonObject
            ?: throw GatewayProtocolException(rpc.endpoint, "config.get returned a non-object payload")
        val sections = payload["parsed"] as? JsonObject ?: payload["config"] as? JsonObject ?: JsonObject(emptyMap())
        val hash = payload.stringOrNull("hash")
        lastHash = CachedHash(rpc, hash)
        return OpenClawConfig(sections = sections, etag = hash)
    }
}
