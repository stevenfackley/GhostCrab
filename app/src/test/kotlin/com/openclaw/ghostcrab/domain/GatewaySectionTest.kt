package com.openclaw.ghostcrab.domain

import com.openclaw.ghostcrab.domain.model.config.AuthMode
import com.openclaw.ghostcrab.domain.model.config.GatewayAuthSection
import com.openclaw.ghostcrab.domain.model.config.GatewayHttpSection
import com.openclaw.ghostcrab.domain.model.config.GatewayMdnsSection
import com.openclaw.ghostcrab.domain.model.config.GatewaySection
import com.openclaw.ghostcrab.domain.model.config.toGatewaySection
import com.openclaw.ghostcrab.domain.model.config.toJsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GatewaySectionTest {

    // ── defaults ──────────────────────────────────────────────────────────────

    @Test fun `default GatewaySection has sensible values`() {
        val section = GatewaySection()
        assertEquals("0.0.0.0", section.http.host)
        assertEquals(18789, section.http.port)
        assertEquals(AuthMode.none, section.auth.mode)
        assertNull(section.auth.token)
        assertTrue(section.mdns.enabled)
        assertEquals("openclaw-gateway", section.mdns.serviceName)
    }

    // ── toGatewaySection ──────────────────────────────────────────────────────

    @Test fun `toGatewaySection parses full JSON correctly`() {
        val json = Json.parseToJsonElement(
            """{"http":{"host":"0.0.0.0","port":8080},""" +
                """"auth":{"mode":"bearer","token":"abc"},""" +
                """"mdns":{"enabled":false,"serviceName":"my-gw"}}""",
        )
        val section = json.toGatewaySection()
        assertEquals(8080, section.http.port)
        assertEquals("0.0.0.0", section.http.host)
        assertEquals(AuthMode.bearer, section.auth.mode)
        assertEquals("abc", section.auth.token)
        assertEquals(false, section.mdns.enabled)
        assertEquals("my-gw", section.mdns.serviceName)
    }

    @Test fun `toGatewaySection uses defaults for missing optional fields`() {
        val json = Json.parseToJsonElement("""{}""")
        val section = json.toGatewaySection()
        assertEquals(GatewaySection(), section)
    }

    @Test fun `toGatewaySection ignores unknown keys`() {
        val json = Json.parseToJsonElement(
            """{"http":{"port":9000},"unknownField":"value","nested":{"also":"ignored"}}"""
        )
        val section = json.toGatewaySection()
        assertEquals(9000, section.http.port)
    }

    @Test fun `toGatewaySection partial http section uses defaults for missing subfields`() {
        val json = Json.parseToJsonElement("""{"http":{"port":1234}}""")
        val section = json.toGatewaySection()
        assertEquals(1234, section.http.port)
        assertEquals("0.0.0.0", section.http.host)
    }

    // ── toJsonElement round-trip ──────────────────────────────────────────────

    @Test fun `toJsonElement round-trips through toGatewaySection`() {
        val original = GatewaySection(
            http = GatewayHttpSection(host = "127.0.0.1", port = 9090),
            auth = GatewayAuthSection(mode = AuthMode.bearer, token = "tok"),
            mdns = GatewayMdnsSection(enabled = false, serviceName = "test-gw"),
        )
        val roundTripped = original.toJsonElement().toGatewaySection()
        assertEquals(original, roundTripped)
    }

    @Test fun `toJsonElement produces JsonObject`() {
        val element = GatewaySection().toJsonElement()
        assertTrue(element is JsonObject)
    }
}
