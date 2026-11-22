package com.openclaw.ghostcrab.domain

import com.openclaw.ghostcrab.domain.exception.GatewayApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GatewayApiExceptionTest {

    private val url = "ws://192.168.1.50:18789/ws"

    @Test
    fun `the config-write codes keep the HTTP-style values the frozen contracts document`() {
        assertEquals(412, GatewayApiException.STATUS_STALE_CONFIG_HASH)
        assertEquals(422, GatewayApiException.STATUS_CONFIG_REJECTED)
    }

    @Test
    fun `only the stale-hash code reads as a stale config hash`() {
        val stale = listOf(200, 400, 401, 403, 404, 412, 422, 500).filter {
            GatewayApiException(url, it).isStaleConfigHash
        }
        assertEquals(listOf(412), stale)
    }

    @Test
    fun `the message is unchanged by the named codes`() {
        assertEquals(
            "Gateway API error at $url: HTTP 412 — config changed since last load",
            GatewayApiException(url, GatewayApiException.STATUS_STALE_CONFIG_HASH, "config changed since last load")
                .message,
        )
    }
}
