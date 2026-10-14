package com.openclaw.ghostcrab.ui

import com.openclaw.ghostcrab.data.api.CleartextBlockedIOException
import com.openclaw.ghostcrab.domain.exception.CleartextBlockedException
import com.openclaw.ghostcrab.domain.exception.GatewayApiException
import com.openclaw.ghostcrab.domain.exception.GatewayProtocolException
import com.openclaw.ghostcrab.domain.exception.GatewayUnreachableException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.IOException
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
    fun `root cause message already in the headline is not repeated`() {
        val msg = GatewayProtocolException(
            "http://10.0.0.5:18789",
            "IllegalArgumentException: bad url",
            IllegalArgumentException("bad url"),
        ).toUserMessage()
        assertEquals(
            "Unexpected gateway response at http://10.0.0.5:18789: IllegalArgumentException: bad url " +
                "(GatewayProtocolException ← IllegalArgumentException)",
            msg,
        )
    }

    @Test
    fun `cleartext block prints the settings advice once`() {
        val msg = CleartextBlockedException(
            "http://8.8.8.8:18789",
            "8.8.8.8",
            CleartextBlockedIOException("8.8.8.8"),
        ).toUserMessage()
        assertEquals(
            "Cleartext HTTP to public IP 8.8.8.8 blocked by app policy. " +
                "Use HTTPS or enable Settings → Connections → Allow HTTP to public IPs. " +
                "URL: http://8.8.8.8:18789 (CleartextBlockedException ← CleartextBlockedIOException)",
            msg,
        )
    }

    @Test
    fun `multi-line root cause collapses to a single line`() {
        val msg = GatewayUnreachableException("http://10.0.0.5:18789", IOException("first line\n   second\tline"))
            .toUserMessage()
        assertEquals(
            "Gateway unreachable at http://10.0.0.5:18789 " +
                "(GatewayUnreachableException ← IOException: first line second line)",
            msg,
        )
    }

    @Test
    fun `exception without message still names its class`() {
        assertEquals("Unexpected failure (IllegalStateException)", IllegalStateException().toUserMessage())
    }
}
