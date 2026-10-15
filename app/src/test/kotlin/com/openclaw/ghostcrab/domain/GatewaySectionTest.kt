package com.openclaw.ghostcrab.domain

import com.openclaw.ghostcrab.domain.model.config.AuthMode
import com.openclaw.ghostcrab.domain.model.config.GatewayAuthSection
import com.openclaw.ghostcrab.domain.model.config.GatewaySection
import com.openclaw.ghostcrab.domain.model.config.OPENCLAW_REDACTED
import com.openclaw.ghostcrab.domain.model.config.toGatewaySection
import com.openclaw.ghostcrab.domain.model.config.toGatewaySectionOrNull
import com.openclaw.ghostcrab.domain.model.config.toJsonElement
import com.openclaw.ghostcrab.testutil.GatewayFixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GatewaySectionTest {

    private val realGateway = GatewayFixtures.frame("res-config-get").getValue("payload").jsonObject
        .getValue("parsed").jsonObject.getValue("gateway")

    // ── decoding ──────────────────────────────────────────────────────────────

    @Test fun `the real 2026_10_3 gateway section decodes`() {
        val section = realGateway.toGatewaySection()
        assertEquals("local", section.mode)
        assertEquals(18789, section.port)
        assertEquals("lan", section.bind)
        assertEquals(AuthMode.Token, section.auth.mode)
        assertEquals(OPENCLAW_REDACTED, section.auth.token)
    }

    @Test fun `every upstream auth mode decodes by its wire name`() {
        listOf(
            "none" to AuthMode.None,
            "token" to AuthMode.Token,
            "password" to AuthMode.Password,
            "trusted-proxy" to AuthMode.TrustedProxy,
        ).forEach { (wire, mode) ->
            assertEquals(mode, Json.parseToJsonElement("""{"auth":{"mode":"$wire"}}""").toGatewaySection().auth.mode)
            assertEquals(wire, mode.wireName)
        }
    }

    @Test fun `an unknown auth mode does not decode so the editor falls back to raw JSON`() {
        assertNull(Json.parseToJsonElement("""{"auth":{"mode":"oauth"}}""").toGatewaySectionOrNull())
    }

    @Test fun `the pre-ADR-0007 bearer mode is not a real mode and falls back to raw JSON`() {
        assertNull(Json.parseToJsonElement("""{"auth":{"mode":"bearer"}}""").toGatewaySectionOrNull())
    }

    @Test fun `toGatewaySectionOrNull decodes a known section`() {
        assertEquals(9000, Json.parseToJsonElement("""{"port":9000}""").toGatewaySectionOrNull()?.port)
    }

    // ── defaults and unknown keys ─────────────────────────────────────────────

    @Test fun `default GatewaySection leaves everything unset except the default port`() {
        val section = GatewaySection()
        assertNull(section.mode)
        assertEquals(18789, section.port)
        assertNull(section.bind)
        assertNull(section.auth.mode)
        assertNull(section.auth.token)
    }

    @Test fun `an empty object decodes to the defaults`() {
        assertEquals(GatewaySection(), Json.parseToJsonElement("""{}""").toGatewaySection())
    }

    @Test fun `missing fields take defaults and unknown keys are ignored`() {
        val section = Json.parseToJsonElement("""{"tailscale":{"mode":"serve"}}""").toGatewaySection()
        assertEquals(GatewaySection(), section)
        assertNull(section.auth.mode)
    }

    @Test fun `unknown keys next to known ones do not disturb decoding`() {
        val json = Json.parseToJsonElement("""{"port":9000,"unknownField":"value","nested":{"also":"ignored"}}""")
        assertEquals(9000, json.toGatewaySection().port)
    }

    // ── encoding ──────────────────────────────────────────────────────────────

    @Test fun `round trip keeps the values`() {
        val original = GatewaySection(
            mode = "local",
            port = 9090,
            bind = "loopback",
            auth = GatewayAuthSection(AuthMode.Password, null),
        )
        assertEquals(original, original.toJsonElement().toGatewaySection())
    }

    @Test fun `auth mode encodes by its wire name`() {
        val encoded = GatewaySection(auth = GatewayAuthSection(AuthMode.TrustedProxy)).toJsonElement()
        assertEquals("""{"auth":{"mode":"trusted-proxy"}}""", encoded.toString())
    }

    @Test fun `toJsonElement produces JsonObject`() {
        assertTrue(GatewaySection().toJsonElement() is JsonObject)
    }

    // ── secrets ───────────────────────────────────────────────────────────────

    @Test fun `toString redacts the token`() {
        val text = GatewayAuthSection(AuthMode.Token, "secret-token").toString()
        assertFalse(text.contains("secret-token"))
        assertTrue(text.contains("[REDACTED]"))
    }
}
