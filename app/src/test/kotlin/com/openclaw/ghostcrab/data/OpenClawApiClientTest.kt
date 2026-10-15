package com.openclaw.ghostcrab.data

import com.openclaw.ghostcrab.data.api.OpenClawApiClient
import com.openclaw.ghostcrab.domain.exception.GatewayApiException
import com.openclaw.ghostcrab.domain.exception.GatewayAuthException
import com.openclaw.ghostcrab.domain.exception.GatewayTimeoutException
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

private const val BASE = "http://192.168.1.50:18789"

private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")

private fun mockClient(vararg responses: Pair<HttpStatusCode, String>): OpenClawApiClient {
    var idx = 0
    val engine = MockEngine {
        val (status, body) = responses[idx++ % responses.size]
        respond(body, status, jsonHeaders())
    }
    val httpClient = HttpClient(engine) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
    }
    return OpenClawApiClient.forTest(BASE, httpClient)
}

class OpenClawApiClientTest {

    // ── health ────────────────────────────────────────────────────────────────

    @Test fun `health returns HealthResponse on 200`() = runTest {
        val client = mockClient(HttpStatusCode.OK to """{"status":"ok"}""")
        val result = client.health()
        assertEquals("ok", result.status)
    }

    @Test fun `health throws GatewayAuthException on 401`() = runTest {
        val client = mockClient(HttpStatusCode.Unauthorized to "{}")
        assertThrows<GatewayAuthException> { client.health() }
    }

    @Test fun `health throws GatewayAuthException on 403`() = runTest {
        val client = mockClient(HttpStatusCode.Forbidden to "{}")
        val ex = assertThrows<GatewayAuthException> { client.health() }
        assertEquals(403, ex.statusCode)
    }

    @Test fun `health throws GatewayApiException on 500`() = runTest {
        val client = mockClient(HttpStatusCode.InternalServerError to "{}")
        val ex = assertThrows<GatewayApiException> { client.health() }
        assertEquals(500, ex.statusCode)
    }

    // Upstream ghcr.io/openclaw/openclaw returns {"status": true} — boolean, not string.
    // Lenient JSON config coerces this into the String DTO; this test guards that assumption.
    @Test fun `health tolerates boolean status field from upstream gateway`() = runTest {
        val client = mockClient(HttpStatusCode.OK to """{"status":true}""")
        client.health()
    }

    // ── isRetryable on GatewayUnreachableException ────────────────────────────

    @Test fun `GatewayUnreachableException is retryable`() {
        assertTrue(GatewayUnreachableException("http://x").isRetryable)
    }

    @Test fun `GatewayAuthException is not retryable`() {
        assertTrue(!GatewayAuthException("http://x", 401).isRetryable)
    }

    @Test fun `GatewayTimeoutException is retryable`() {
        assertTrue(GatewayTimeoutException("http://x").isRetryable)
    }

    @Test fun `GatewayApiException is not retryable`() {
        assertTrue(!GatewayApiException("http://x", 500).isRetryable)
    }
}
