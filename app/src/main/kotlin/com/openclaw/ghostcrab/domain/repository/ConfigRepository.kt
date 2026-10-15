package com.openclaw.ghostcrab.domain.repository

import com.openclaw.ghostcrab.domain.exception.GatewayApiException
import com.openclaw.ghostcrab.domain.exception.ConfigValidationException
import com.openclaw.ghostcrab.domain.model.OpenClawConfig
import kotlinx.serialization.json.JsonElement

/**
 * Reads and writes the OpenClaw Gateway's `openclaw.json` configuration
 * (ADR-0007: `config.get` / `config.patch` with a `baseHash` conflict guard over the session socket).
 *
 * Optimistic UI is **forbidden** — the gateway is the source of truth.
 * After every [updateConfig], callers must call [getConfig] to reconcile.
 *
 * **Contract frozen at v1.0.**
 */
interface ConfigRepository {

    /**
     * Fetches the full configuration from the gateway (`config.get`; secrets come back redacted).
     *
     * @return The current [OpenClawConfig]: its sections and the gateway's config `hash` as [OpenClawConfig.etag].
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayMissingScopeException without `operator.admin`.
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayNotConnectedException if no session is active.
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayException on any other gateway failure.
     */
    suspend fun getConfig(): OpenClawConfig

    /**
     * Applies a JSON merge-patch update to a single top-level config section.
     *
     * Does **not** perform an optimistic local update. Callers must call [getConfig]
     * afterward to read the authoritative post-write state.
     *
     * The write carries the config `hash` it was made against (`config.patch` `baseHash`). If the config changed
     * since then (another client wrote it), or no hash was available, the gateway refuses it and the
     * implementation throws a [GatewayApiException] with `statusCode = 412`: reload with [getConfig], then retry.
     *
     * @param section Top-level section key to update (e.g. `"gateway"`).
     * @param value JSON merge-patch value for the section.
     * @throws ConfigValidationException if the gateway's schema check reports exactly one issue and it lies inside
     *   [section]; [ConfigValidationException.field] is its full config path (e.g. `gateway.port`).
     * @throws GatewayApiException 412 as above; 422 for any other rejected patch, including issues outside [section]
     *   (the gateway validates the whole merged config) or several issues (the body is the gateway's text plus
     *   every issue).
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayMissingScopeException without `operator.admin`.
     */
    suspend fun updateConfig(section: String, value: JsonElement)
}
