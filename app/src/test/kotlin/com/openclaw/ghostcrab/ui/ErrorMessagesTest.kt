package com.openclaw.ghostcrab.ui

import com.openclaw.ghostcrab.domain.exception.GatewayApiException
import com.openclaw.ghostcrab.domain.exception.GatewayUnreachableException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.ConnectException

class ErrorMessagesTest {

    @Test
    fun `api error shows url status and class`() {
        val msg = GatewayApiException("http://10.0.0.5:18789/config/gateway", 412).toUserMessage()
        assertEquals(
            "Gateway API error at http://10.0.0.5:18789/config/gateway: HTTP 412 (GatewayApiException)",
            msg,
        )
    }

    @Test
    fun `wrapped transport failure names the root cause`() {
        val msg = GatewayUnreachableException("http://10.0.0.5:18789", ConnectException("Connection refused"))
            .toUserMessage()
        assertEquals(
            "Gateway unreachable at http://10.0.0.5:18789 " +
                "(GatewayUnreachableException ← ConnectException: Connection refused)",
            msg,
        )
    }

    @Test
    fun `exception without message still names its class`() {
        assertEquals("Unexpected failure (IllegalStateException)", IllegalStateException().toUserMessage())
    }
}
