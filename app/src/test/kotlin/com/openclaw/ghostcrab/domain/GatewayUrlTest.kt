package com.openclaw.ghostcrab.domain

import com.openclaw.ghostcrab.domain.model.DiscoveredGateway
import com.openclaw.ghostcrab.domain.util.DEFAULT_GATEWAY_PORT
import com.openclaw.ghostcrab.domain.util.GatewayUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class GatewayUrlTest {

    @Test fun `parses an http url with explicit port`() {
        assertEquals(GatewayUrl("http", "192.168.1.50", 9000), GatewayUrl.parse("http://192.168.1.50:9000"))
    }

    @Test fun `missing port defaults to 18789 for http and 443 for https`() {
        assertEquals(GatewayUrl("http", "gw.local", DEFAULT_GATEWAY_PORT), GatewayUrl.parse("http://gw.local"))
        assertEquals(GatewayUrl("https", "gw.example", 443), GatewayUrl.parse(" HTTPS://gw.example/ "))
    }

    @Test fun `rejects non-http schemes, userinfo and garbage`() {
        listOf("ftp://h:1", "http://user:pw@h:1", "just text", "").forEach { assertNull(GatewayUrl.parse(it), it) }
    }

    @Test fun `ipv6 literals are bracketed and zone ids stripped`() {
        assertEquals("http://[fe80::1]:18789", GatewayUrl.of("fe80::1%wlan0", 18789).toString())
        assertEquals(GatewayUrl("http", "fe80::1", 18789), GatewayUrl.parse("http://[fe80::1]:18789"))
    }

    @Test fun `out-of-range ports are rejected, not defaulted`() {
        assertNull(GatewayUrl.parse("http://h:0"))
        assertNull(GatewayUrl.parse("http://h:65536"))
    }

    @Test fun `zone ids are stripped when parsing`() {
        assertEquals("fe80::1", GatewayUrl.parse("http://[fe80::1%wlan0]:18789")?.host)
        assertEquals("fe80::1", GatewayUrl.parse("http://[fe80::1%25wlan0]:18789")?.host)
    }

    @Test fun `constructor rejects invalid values`() {
        assertThrows(IllegalArgumentException::class.java) { GatewayUrl("http", "[::1]", 0) }
        assertThrows(IllegalArgumentException::class.java) { GatewayUrl("ftp", "h", 1) }
        assertThrows(IllegalArgumentException::class.java) { GatewayUrl("http", " ", 1) }
    }

    @Test fun `discovered IPv6 gateway produces a valid URL`() {
        val gw = DiscoveredGateway("gw", "fe80::1%wlan0", 18789, "gw", null)
        assertEquals("http://[fe80::1]:18789", gw.url)
    }
}
