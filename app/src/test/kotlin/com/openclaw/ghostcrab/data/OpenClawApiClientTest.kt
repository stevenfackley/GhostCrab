package com.openclaw.ghostcrab.data

import com.openclaw.ghostcrab.data.api.OpenClawApiClient
import com.openclaw.ghostcrab.domain.exception.ConfigValidationException
import com.openclaw.ghostcrab.domain.exception.GatewayApiException
import com.openclaw.ghostcrab.domain.exception.GatewayAuthException
import com.openclaw.ghostcrab.domain.exception.GatewayTimeoutException
import com.openclaw.ghostcrab.domain.exception.GatewayUnreachableException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 5_000
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

    // ── status ────────────────────────────────────────────────────────────────

    @Test fun `status returns StatusResponse on 200`() = runTest {
        val body = """{"displayName":"My GW","version":"1.0","capabilities":["skill-ai-recommend"]}"""
        val client = mockClient(HttpStatusCode.OK to body)
        val result = client.status()
        assertEquals("My GW", result.displayName)
        assertEquals("1.0", result.version)
        assertEquals(listOf("skill-ai-recommend"), result.capabilities)
    }

    @Test fun `status returns null hardware when field absent`() = runTest {
        val client = mockClient(HttpStatusCode.OK to """{"displayName":"GW","version":"1.0","capabilities":[]}""")
        assertNull(client.status().hardware)
    }

    @Test fun `status throws GatewayAuthException on 401`() = runTest {
        val client = mockClient(HttpStatusCode.Unauthorized to "{}")
        val ex = assertThrows<GatewayAuthException> { client.status() }
        assertEquals(401, ex.statusCode)
    }

    // ── getConfig ─────────────────────────────────────────────────────────────

    @Test fun `getConfig returns sections and ETag`() = runTest {
        val body = """{"gateway":{"http":{"port":18789}}}"""
        val engine = MockEngine {
            respond(body, HttpStatusCode.OK, Headers.build {
                append(HttpHeaders.ContentType, "application/json")
                append(HttpHeaders.ETag, "etag-abc")
            })
        }
        val client = OpenClawApiClient.forTest(BASE, HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        })
        val (sections, etag) = client.getConfig()
        assertNotNull(sections["gateway"])
        assertEquals("etag-abc", etag)
    }

    @Test fun `getConfig returns null ETag when header absent`() = runTest {
        val client = mockClient(HttpStatusCode.OK to """{"gateway":{}}""")
        val (_, etag) = client.getConfig()
        assertNull(etag)
    }

    // ── updateConfig ──────────────────────────────────────────────────────────

    @Test fun `updateConfig succeeds on 200`() = runTest {
        val client = mockClient(HttpStatusCode.OK to "{}")
        client.updateConfig("gateway", JsonPrimitive("val"), etag = null)
    }

    @Test fun `updateConfig succeeds on 204`() = runTest {
        val client = mockClient(HttpStatusCode.NoContent to "")
        client.updateConfig("gateway", JsonPrimitive("val"), etag = null)
    }

    @Test fun `updateConfig 412 throws GatewayApiException with statusCode 412`() = runTest {
        val client = mockClient(HttpStatusCode.PreconditionFailed to "{}")
        val ex = assertThrows<GatewayApiException> { client.updateConfig("gateway", JsonPrimitive("x"), "stale") }
        assertEquals(412, ex.statusCode)
    }

    @Test fun `updateConfig 422 with field+reason throws ConfigValidationException`() = runTest {
        val body = """{"field":"gateway.http.port","reason":"must be 1–65535"}"""
        val client = mockClient(HttpStatusCode.UnprocessableEntity to body)
        val ex = assertThrows<ConfigValidationException> {
            client.updateConfig("gateway", JsonPrimitive(-1), null)
        }
        assertEquals("gateway.http.port", ex.field)
        assertEquals("must be 1–65535", ex.reason)
    }

    @Test fun `updateConfig 422 without field+reason throws GatewayApiException`() = runTest {
        val client = mockClient(HttpStatusCode.UnprocessableEntity to """{"error":"bad"}""")
        val ex = assertThrows<GatewayApiException> {
            client.updateConfig("gateway", JsonPrimitive("x"), null)
        }
        assertEquals(422, ex.statusCode)
    }

    // ── getModels ─────────────────────────────────────────────────────────────

    @Test fun `getModels returns mapped list`() = runTest {
        val body = """[{"id":"gpt-4o","provider":"openai","isActive":true}]"""
        val client = mockClient(HttpStatusCode.OK to body)
        val models = client.getModels()
        assertEquals(1, models.size)
        assertEquals("gpt-4o", models[0].id)
        assertEquals("openai", models[0].provider)
        assertTrue(models[0].isActive)
    }

    @Test fun `getModels displayName defaults to id when absent`() = runTest {
        val client = mockClient(HttpStatusCode.OK to """[{"id":"llama3","provider":"ollama"}]""")
        assertEquals("llama3", client.getModels()[0].displayName)
    }

    @Test fun `getModels returns empty list for empty array`() = runTest {
        val client = mockClient(HttpStatusCode.OK to "[]")
        assertTrue(client.getModels().isEmpty())
    }

    @Test fun `getModels throws GatewayAuthException on 403`() = runTest {
        val client = mockClient(HttpStatusCode.Forbidden to "{}")
        assertThrows<GatewayAuthException> { client.getModels() }
    }

    // ── setActiveModel ────────────────────────────────────────────────────────

    @Test fun `setActiveModel succeeds on 200`() = runTest {
        val client = mockClient(HttpStatusCode.OK to "{}")
        client.setActiveModel("gpt-4o")
    }

    @Test fun `setActiveModel succeeds on 204`() = runTest {
        val client = mockClient(HttpStatusCode.NoContent to "")
        client.setActiveModel("gpt-4o")
    }

    @Test fun `setActiveModel throws GatewayApiException on 404`() = runTest {
        val client = mockClient(HttpStatusCode.NotFound to "{}")
        val ex = assertThrows<GatewayApiException> { client.setActiveModel("unknown") }
        assertEquals(404, ex.statusCode)
    }

    // ── getAIRecommendation ───────────────────────────────────────────────────

    @Test fun `getAIRecommendation returns mapped response on 200`() = runTest {
        val body = """{"recommendation":"Use HTTPS","suggested_changes":[]}"""
        val client = mockClient(HttpStatusCode.OK to body)
        val dto = client.getAIRecommendation(
            com.openclaw.ghostcrab.data.api.dto.AIRecommendationRequestDto(
                query = "how to secure?",
                context = com.openclaw.ghostcrab.data.api.dto.AIContextDto(
                    activeConfig = kotlinx.serialization.json.JsonObject(emptyMap()),
                    hardwareInfo = null,
                    activeModelId = null,
                )
            )
        )
        assertEquals("Use HTTPS", dto.recommendation)
        assertTrue(dto.suggestedChanges.isEmpty())
    }

    @Test fun `getAIRecommendation 404 throws GatewayApiException with statusCode 404`() = runTest {
        val client = mockClient(HttpStatusCode.NotFound to "{}")
        val ex = assertThrows<GatewayApiException> {
            client.getAIRecommendation(
                com.openclaw.ghostcrab.data.api.dto.AIRecommendationRequestDto(
                    query = "q",
                    context = com.openclaw.ghostcrab.data.api.dto.AIContextDto(
                        activeConfig = kotlinx.serialization.json.JsonObject(emptyMap()),
                        hardwareInfo = null,
                        activeModelId = null,
                    )
                )
            )
        }
        assertEquals(404, ex.statusCode)
    }

    @Test fun `getAIRecommendation 429 throws GatewayApiException with statusCode 429`() = runTest {
        val client = mockClient(HttpStatusCode.TooManyRequests to "{}")
        val ex = assertThrows<GatewayApiException> {
            client.getAIRecommendation(
                com.openclaw.ghostcrab.data.api.dto.AIRecommendationRequestDto(
                    query = "q",
                    context = com.openclaw.ghostcrab.data.api.dto.AIContextDto(
                        activeConfig = kotlinx.serialization.json.JsonObject(emptyMap()),
                        hardwareInfo = null,
                        activeModelId = null,
                    )
                )
            )
        }
        assertEquals(429, ex.statusCode)
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
