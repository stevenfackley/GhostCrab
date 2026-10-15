package com.openclaw.ghostcrab.data

import com.openclaw.ghostcrab.data.api.CleartextBlockedIOException
import com.openclaw.ghostcrab.data.api.OpenClawApiClient
import com.openclaw.ghostcrab.domain.exception.CleartextBlockedException
import com.openclaw.ghostcrab.domain.exception.GatewayProtocolException
import com.openclaw.ghostcrab.domain.exception.GatewayTimeoutException
import com.openclaw.ghostcrab.domain.exception.GatewayTlsException
import com.openclaw.ghostcrab.domain.exception.GatewayUnreachableException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

private const val ERR_BASE = "http://192.168.1.50:18789"

private fun throwingClient(error: Throwable): OpenClawApiClient =
    OpenClawApiClient.forTest(ERR_BASE, HttpClient(MockEngine { throw error }))

class OpenClawApiClientErrorMappingTest {

    @Test fun `socket timeout maps to GatewayTimeoutException`() = runTest {
        assertThrows<GatewayTimeoutException> { throwingClient(SocketTimeoutException("read timed out")).health() }
    }

    @Test fun `TLS failure maps to GatewayTlsException`() = runTest {
        assertThrows<GatewayTlsException> { throwingClient(SSLHandshakeException("bad cert")).health() }
    }

    @Test fun `unknown host maps to GatewayUnreachableException`() = runTest {
        assertThrows<GatewayUnreachableException> { throwingClient(UnknownHostException("nope.local")).health() }
    }

    @Test fun `refused connection maps to GatewayUnreachableException`() = runTest {
        assertThrows<GatewayUnreachableException> { throwingClient(ConnectException("refused")).health() }
    }

    @Test fun `cleartext policy block maps to CleartextBlockedException with the host`() = runTest {
        val ex = assertThrows<CleartextBlockedException> {
            throwingClient(CleartextBlockedIOException("8.8.8.8")).health()
        }
        assertEquals("8.8.8.8", ex.host)
        assertTrue(ex.message.orEmpty().contains("Settings → Connections → Allow HTTP to public IPs"))
    }

    @Test fun `non-IO failure maps to GatewayProtocolException, not Unreachable`() = runTest {
        val ex = assertThrows<GatewayProtocolException> {
            throwingClient(IllegalArgumentException("bad url")).health()
        }
        assertTrue(ex.detail.contains("IllegalArgumentException"))
    }

    @Test fun `openWsSession refused upgrade maps to GatewayUnreachableException`() = runTest {
        val client = OpenClawApiClient.forTest(
            ERR_BASE,
            HttpClient(MockEngine { throw ConnectException("refused") }) { install(WebSockets) },
        )
        val ex = assertThrows<GatewayUnreachableException> { client.openWsSession() }
        assertTrue(ex.cause is ConnectException)
    }

    @Test fun `openWsSession to a refusing port maps to GatewayUnreachableException`() = runTest {
        // Loopback port 1 refuses at once; real OkHttp so the upgrade really goes out.
        val client = OpenClawApiClient.forTest("http://127.0.0.1:1", HttpClient(OkHttp) { install(WebSockets) })
        try {
            assertThrows<GatewayUnreachableException> { client.openWsSession() }
        } finally {
            client.close()
        }
    }

    @Test fun `webSocketUrl maps http to ws and https to wss`() {
        val engine = MockEngine { error("not called") }
        val plain = OpenClawApiClient.forTest(ERR_BASE, HttpClient(engine))
        val tls = OpenClawApiClient.forTest("https://gw.example:443/", HttpClient(engine))
        assertEquals("ws://192.168.1.50:18789/ws", plain.webSocketUrl)
        assertEquals("wss://gw.example:443/ws", tls.webSocketUrl)
    }
}
