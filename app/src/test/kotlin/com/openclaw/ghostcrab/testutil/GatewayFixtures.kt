package com.openclaw.ghostcrab.testutil

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Frames captured from `ghcr.io/openclaw/openclaw` 2026.10.3 during roadmap M5
 * (`docs/QA_REAL_GATEWAY_2026-10.md`), trimmed, with the issued device token replaced by `dt-test-0001`.
 * Source directory: `fixtures/gateway-protocol/2026.10.3/` (a test resource root, see `app/build.gradle.kts`).
 */
object GatewayFixtures {

    /** Gateway release the frames were captured from. */
    const val VERSION = "2026.10.3"

    /** Every fixture name, without the `.json` suffix. */
    val ALL = listOf(
        "challenge", "hello-ok-paired", "hello-ok-noscope",
        "err-pairing-required", "err-token-mismatch", "err-token-missing", "err-device-identity-required",
        "err-missing-scope", "err-unknown-method", "err-skill-not-found",
        "res-health", "res-skills-status", "res-models-list", "res-config-get",
        "err-client-id-invalid", "err-pairing-metadata-upgrade",
        "err-config-stale-hash", "err-config-base-hash-required", "err-config-invalid", "res-config-patch",
        "res-skills-update", "evt-config-changed", "res-skills-install",
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @param name Fixture name without `.json`.
     * @return The raw frame text.
     * @throws IllegalStateException if the fixture is not on the test classpath.
     */
    fun text(name: String): String {
        val path = "/$VERSION/$name.json"
        val stream = GatewayFixtures::class.java.getResourceAsStream(path)
            ?: error("missing test fixture $path (fixtures/gateway-protocol must be a test resource dir)")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /**
     * @param name Fixture name without `.json`.
     * @return The parsed frame.
     */
    fun frame(name: String): JsonObject = json.parseToJsonElement(text(name)).jsonObject
}
