package com.openclaw.ghostcrab.data

import com.openclaw.ghostcrab.data.api.CleartextPublicIpInterceptor
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CleartextPublicIpInterceptorTest {

    // ── isPublicIpLiteral ─────────────────────────────────────────────────────

    @Test
    fun `private RFC-1918 192_168 is not public`() {
        assertFalse(CleartextPublicIpInterceptor.isPublicIpLiteral("192.168.1.50"))
    }

    @Test
    fun `private RFC-1918 10_x is not public`() {
        assertFalse(CleartextPublicIpInterceptor.isPublicIpLiteral("10.0.0.1"))
    }

    @Test
    fun `private RFC-1918 172_16 is not public`() {
        assertFalse(CleartextPublicIpInterceptor.isPublicIpLiteral("172.16.100.5"))
    }

    @Test
    fun `loopback 127_0_0_1 is not public`() {
        assertFalse(CleartextPublicIpInterceptor.isPublicIpLiteral("127.0.0.1"))
    }

    @Test
    fun `link-local 169_254 is not public`() {
        assertFalse(CleartextPublicIpInterceptor.isPublicIpLiteral("169.254.1.1"))
    }

    @Test
    fun `hostname is not public`() {
        assertFalse(CleartextPublicIpInterceptor.isPublicIpLiteral("my-gateway.local"))
    }

    @Test
    fun `public IP 8_8_8_8 is public`() {
        assertTrue(CleartextPublicIpInterceptor.isPublicIpLiteral("8.8.8.8"))
    }

    @Test
    fun `public IP 203_0_113_x is public`() {
        assertTrue(CleartextPublicIpInterceptor.isPublicIpLiteral("203.0.113.50"))
    }

    @Test
    fun `invalid IP string is not public`() {
        assertFalse(CleartextPublicIpInterceptor.isPublicIpLiteral("999.999.999.999"))
    }
}
