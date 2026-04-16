package com.openclaw.ghostcrab.data.impl

import com.openclaw.ghostcrab.domain.model.OpenClawConfig
import com.openclaw.ghostcrab.domain.repository.ConfigRepository
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.atomic.AtomicReference

/**
 * Production [ConfigRepository] backed by [GatewayConnectionManagerImpl].
 *
 * Caches the most recently seen ETag so that [updateConfig] can send an `If-Match` header,
 * enabling optimistic-concurrency detection (HTTP 412) on the gateway side.
 *
 * @param connectionManager Concrete manager used to obtain the active [com.openclaw.ghostcrab.data.api.OpenClawApiClient].
 */
class ConfigRepositoryImpl(
    private val connectionManager: GatewayConnectionManagerImpl,
) : ConfigRepository {

    private val lastEtag = AtomicReference<String?>(null)

    /**
     * Fetches the full config from the gateway and caches the returned ETag.
     *
     * @return [OpenClawConfig] with all sections and the latest ETag.
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayApiException on HTTP error.
     * @throws IllegalStateException if no active connection exists.
     */
    override suspend fun getConfig(): OpenClawConfig {
        val client = connectionManager.requireClient()
        val (sections, etag) = client.getConfig()
        lastEtag.set(etag)
        return OpenClawConfig(sections = sections, etag = etag)
    }

    /**
     * Sends a JSON merge-patch for [section] using the cached ETag for conflict detection.
     *
     * @param section Top-level config section key.
     * @param value JSON merge-patch value.
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayApiException on HTTP error, including 412.
     * @throws com.openclaw.ghostcrab.domain.exception.ConfigValidationException on server-side validation failure.
     * @throws IllegalStateException if no active connection exists.
     */
    override suspend fun updateConfig(section: String, value: JsonElement) {
        val client = connectionManager.requireClient()
        client.updateConfig(section, value, lastEtag.get())
    }
}
