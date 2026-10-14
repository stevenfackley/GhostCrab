package com.openclaw.ghostcrab.data.impl

import com.openclaw.ghostcrab.data.api.OpenClawApiClient
import com.openclaw.ghostcrab.domain.model.OpenClawConfig
import com.openclaw.ghostcrab.domain.repository.ConfigRepository
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.atomic.AtomicReference

/**
 * Production [ConfigRepository] backed by [GatewayConnectionManagerImpl].
 *
 * Caches the ETag of the most recent read **or write** so the next [updateConfig] sends a current
 * `If-Match`. The cache is bound to the session client that produced it: after a reconnect the old
 * tag is never sent. When a write response carries no ETag the cache becomes `null` (the pre-write
 * tag is stale by definition and would only earn a 412).
 *
 * @param connectionManager Concrete manager used to obtain the active [OpenClawApiClient].
 */
class ConfigRepositoryImpl(
    private val connectionManager: GatewayConnectionManagerImpl,
) : ConfigRepository {

    private class CachedEtag(val client: OpenClawApiClient, val etag: String?)

    private val lastEtag = AtomicReference<CachedEtag?>(null)

    /**
     * Fetches the full config from the gateway and caches the returned ETag for this session.
     *
     * @return [OpenClawConfig] with all sections and the latest ETag.
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayException on any gateway failure.
     * @throws IllegalStateException if no active connection exists.
     */
    override suspend fun getConfig(): OpenClawConfig {
        val client = connectionManager.requireClient()
        val (sections, etag) = client.getConfig()
        lastEtag.set(CachedEtag(client, etag))
        return OpenClawConfig(sections = sections, etag = etag)
    }

    /**
     * Sends a JSON merge-patch for [section] with the current session's cached ETag, then caches the
     * ETag returned by the write.
     *
     * @param section Top-level config section key.
     * @param value JSON merge-patch value.
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayApiException on HTTP error, including 412.
     * @throws com.openclaw.ghostcrab.domain.exception.ConfigValidationException on server-side validation failure.
     * @throws IllegalStateException if no active connection exists.
     */
    override suspend fun updateConfig(section: String, value: JsonElement) {
        val client = connectionManager.requireClient()
        val cached = lastEtag.get()?.takeIf { it.client === client }?.etag
        val newEtag = client.updateConfig(section, value, cached)
        lastEtag.set(CachedEtag(client, newEtag))
    }
}
