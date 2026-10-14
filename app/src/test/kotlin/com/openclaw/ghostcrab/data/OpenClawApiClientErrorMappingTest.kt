package com.openclaw.ghostcrab.data

import com.openclaw.ghostcrab.data.api.CleartextBlockedIOException
import com.openclaw.ghostcrab.data.api.OpenClawApiClient
import com.openclaw.ghostcrab.data.api.dto.AIContextDto
import com.openclaw.ghostcrab.data.api.dto.AIRecommendationRequestDto
import com.openclaw.ghostcrab.domain.exception.CleartextBlockedException
import com.openclaw.ghostcrab.domain.exception.GatewayProtocolException
import com.openclaw.ghostcrab.domain.exception.GatewayTimeoutException
import com.openclaw.ghostcrab.domain.exception.GatewayTlsException
import com.openclaw.ghostcrab.domain.exception.GatewayUnreachableException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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

private fun jsonResponseClient(status: HttpStatusCode, body: String): OpenClawApiClient {
    val engine = MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }
    return OpenClawApiClient.forTest(ERR_BASE, HttpClient(engine) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    })
}

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
        assertTrue(ex.message.orEmpty().contains("Settings"))
    }

    @Test fun `non-IO failure maps to GatewayProtocolException, not Unreachable`() = runTest {
        val ex = assertThrows<GatewayProtocolException> {
            throwingClient(IllegalArgumentException("bad url")).health()
        }
        assertTrue(ex.detail.contains("IllegalArgumentException"))
    }

    @Test fun `a 202 from ai recommend is decoded instead of reported unreachable`() = runTest {
        val client = jsonResponseClient(HttpStatusCode.Accepted, """{"recommendation":"ok","suggested_changes":[]}""")
        val dto = client.getAIRecommendation(AIRecommendationRequestDto("q", AIContextDto(null, null, null)))
        assertEquals("ok", dto.recommendation)
    }
}
