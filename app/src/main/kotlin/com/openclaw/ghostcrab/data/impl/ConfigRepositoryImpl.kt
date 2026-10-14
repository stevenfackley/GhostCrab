package com.openclaw.ghostcrab.data.impl

import com.openclaw.ghostcrab.data.api.OpenClawApiClient
import com.openclaw.ghostcrab.domain.model.OpenClawConfig
import com.openclaw.ghostcrab.domain.repository.ConfigRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement

/**
 * Production [ConfigRepository] backed by [GatewayConnectionManagerImpl].
 *
 * Caches the ETag of the most recent read **or write** so the next [updateConfig] sends a current
 * `If-Match`. The cache is bound to the session client that produced it: after a reconnect the old
 * tag is never sent. When a write response carries no ETag the cache becomes `null` (the pre-write
 * tag is stale by definition and would only earn a 412).
 *
 * Concurrency: reads and writes run one at a time under a [Mutex] held across the network call, so
 * the cached tag always comes from the request that completed last. Without it, a slow read that
 * overlaps a write could land after the write and replace the write's newer tag with its own stale one.
 *
 * @param connectionManager Concrete manager used to obtain the active [OpenClawApiClient].
 */
class ConfigRepositoryImpl(
    private val connectionManager: GatewayConnectionManagerImpl,
) : ConfigRepository {

    private class CachedEtag(val client: OpenClawApiClient, val etag: String?)

    private val mutex = Mutex()

    /** Guarded by [mutex]. */
    private var lastEtag: CachedEtag? = null

    /**
     * Fetches the full config from the gateway and caches the returned ETag for this session.
     * Waits for any in-flight [getConfig] / [updateConfig] on this repository to finish first.
     *
     * @return [OpenClawConfig] with all sections and the latest ETag.
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayException on any gateway failure.
     * @throws IllegalStateException if no active connection exists.
     */
    override suspend fun getConfig(): OpenClawConfig = mutex.withLock {
        val client = connectionManager.requireClient()
        val (sections, etag) = client.getConfig()
        lastEtag = CachedEtag(client, etag)
        OpenClawConfig(sections = sections, etag = etag)
    }

    /**
     * Sends a JSON merge-patch for [section] with the current session's cached ETag, then caches the
     * ETag returned by the write. Waits for any in-flight [getConfig] / [updateConfig] on this
     * repository to finish first.
     *
     * @param section Top-level config section key.
     * @param value JSON merge-patch value.
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayApiException on HTTP error, including 412.
     * @throws com.openclaw.ghostcrab.domain.exception.ConfigValidationException on server-side validation failure.
     * @throws IllegalStateException if no active connection exists.
     */
    override suspend fun updateConfig(section: String, value: JsonElement): Unit = mutex.withLock {
        val client = connectionManager.requireClient()
        val cached = lastEtag?.takeIf { it.client === client }?.etag
        val newEtag = client.updateConfig(section, value, cached)
        lastEtag = CachedEtag(client, newEtag)
    }
}
