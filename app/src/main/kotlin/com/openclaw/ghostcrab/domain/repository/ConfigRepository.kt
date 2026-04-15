package com.openclaw.ghostcrab.domain.repository

import com.openclaw.ghostcrab.domain.exception.GatewayApiException
import com.openclaw.ghostcrab.domain.exception.ConfigValidationException
import com.openclaw.ghostcrab.domain.model.OpenClawConfig
import kotlinx.serialization.json.JsonElement

/**
 * Reads and writes the OpenClaw Gateway's `openclaw.json` configuration.
 *
 * Optimistic UI is **forbidden** — the gateway is the source of truth.
 * After every [updateConfig], callers must call [getConfig] to reconcile.
 *
 * **Contract frozen at v1.0.**
 */
interface ConfigRepository {

    /**
     * Fetches the full configuration from the gateway.
     *
     * @return The current [OpenClawConfig] with all sections and optional ETag.
     * @throws GatewayApiException if the gateway returns an unexpected HTTP error.
     */
    suspend fun getConfig(): OpenClawConfig

    /**
     * Applies a JSON merge-patch update to a single top-level config section.
     *
     * Does **not** perform an optimistic local update. Callers must call [getConfig]
     * afterward to read the authoritative post-write state.
     *
     * If the gateway supports ETags and the config has been modified by another client
     * since [getConfig] was called, the gateway returns 412; the implementation maps
     * this to a [GatewayApiException] with `statusCode = 412`.
     *
     * @param section Top-level section key to update (e.g. `"gateway"`).
     * @param value JSON merge-patch value for the section.
     * @throws ConfigValidationException if the value fails server-side validation.
     * @throws GatewayApiException on unexpected gateway errors.
     */
    suspend fun updateConfig(section: String, value: JsonElement)
}
