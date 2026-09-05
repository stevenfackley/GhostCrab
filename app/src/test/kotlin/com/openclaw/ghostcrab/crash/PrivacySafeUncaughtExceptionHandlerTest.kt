package com.openclaw.ghostcrab.crash

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PrivacySafeUncaughtExceptionHandlerTest {

    @Test
    fun `sanitize redacts bearer token`() {
        val input = "GatewayAuthException: request failed\n  Authorization: Bearer abc123xyz\n  at Foo.kt:10"
        val result = PrivacySafeUncaughtExceptionHandler.sanitize(input)
        assertFalse(result.contains("abc123xyz"))
        assertTrue(result.contains("Bearer [REDACTED]"))
    }

    @Test
    fun `sanitize redacts URL credentials`() {
        val input = "GatewayUnreachableException at http://admin:password123@192.168.1.50:18789/health"
        val result = PrivacySafeUncaughtExceptionHandler.sanitize(input)
        assertFalse(result.contains("password123"))
        assertTrue(result.contains("[REDACTED]@"))
        assertTrue(result.contains("[IP]:18789"))
    }

    @Test
    fun `sanitize leaves clean text unchanged`() {
        val input = "NullPointerException at MainActivity.onCreate(MainActivity.kt:42)"
        val result = PrivacySafeUncaughtExceptionHandler.sanitize(input)
        assertEquals(input, result)
    }

    @Test
    fun `sanitize redacts IPv4 literals`() {
        val input = "GatewayUnreachableException: Gateway unreachable at http://10.0.0.7:18789/health"
        val result = PrivacySafeUncaughtExceptionHandler.sanitize(input)
        assertFalse(result.contains("10.0.0.7"))
        assertTrue(result.contains("http://[IP]:18789/health"))
    }

    @Test
    fun `sanitize redacts token query params and JSON fields`() {
        val input = """url=http://gw?token=abc123 body={"auth":{"token": "s3cr3t"},"api_key":"k-9"} access_token=zzz"""
        val result = PrivacySafeUncaughtExceptionHandler.sanitize(input)
        assertFalse(result.contains("abc123"))
        assertFalse(result.contains("s3cr3t"))
        assertFalse(result.contains("k-9"))
        assertFalse(result.contains("zzz"))
        assertTrue(result.contains("token=[REDACTED]"))
        assertTrue(result.contains("\"token\": \"[REDACTED]"))
    }
}
