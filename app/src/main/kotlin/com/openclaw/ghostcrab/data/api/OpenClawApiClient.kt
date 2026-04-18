package com.openclaw.ghostcrab.data.api

import com.openclaw.ghostcrab.data.api.dto.AIRecommendationRequestDto
import com.openclaw.ghostcrab.data.api.dto.AIRecommendationResponseDto
import com.openclaw.ghostcrab.data.api.dto.HealthResponse
import com.openclaw.ghostcrab.data.api.dto.ModelDto
import com.openclaw.ghostcrab.data.api.dto.StatusResponse
import com.openclaw.ghostcrab.domain.exception.ConfigValidationException
import com.openclaw.ghostcrab.domain.exception.GatewayApiException
import com.openclaw.ghostcrab.domain.exception.GatewayAuthException
import com.openclaw.ghostcrab.domain.exception.GatewayException
import com.openclaw.ghostcrab.domain.exception.GatewayTimeoutException
import com.openclaw.ghostcrab.domain.exception.GatewayTlsException
import com.openclaw.ghostcrab.domain.exception.GatewayUnreachableException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Ktor-backed HTTP client for the OpenClaw Gateway API.
 *
 * Create via [unauthenticated] for probing or [authenticated] for a live session.
 * Call [close] when the session ends to release underlying connections.
 *
 * **Authorization headers are never logged.** Verify with `adb logcat | grep -i bearer`.
 *
 * Timeouts: connect = 15s, request = 30s.
 */
class OpenClawApiClient private constructor(
    val baseUrl: String,
    private val httpClient: HttpClient,
) {

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * GET `/health` — unauthenticated liveness check.
     *
     * @throws GatewayUnreachableException if the host cannot be reached.
     * @throws GatewayTimeoutException if the request times out.
     * @throws GatewayTlsException on SSL/TLS failure.
     */
    suspend fun health(): HealthResponse = safeRequest(baseUrl) {
        httpClient.get("$baseUrl/health").let { response ->
            response.mapErrors(baseUrl)
            response.body()
        }
    }

    /**
     * GET `/status` — returns gateway identity, version, and capabilities.
     *
     * May require authentication depending on gateway configuration.
     *
     * Some gateways (e.g. upstream `ghcr.io/openclaw/openclaw:latest`) serve an HTML admin UI
     * at `/status` instead of a JSON payload. In that case we return a default [StatusResponse]
     * so the connect flow still succeeds — the gateway is clearly reachable.
     *
     * @throws GatewayAuthException if auth is required and no/invalid token is present.
     * @throws GatewayUnreachableException if the host cannot be reached.
     * @throws GatewayTimeoutException if the request times out.
     */
    suspend fun status(): StatusResponse = safeRequest(baseUrl) {
        val response = httpClient.get("$baseUrl/status")
        response.mapErrors(baseUrl)
        val contentType = response.headers["Content-Type"].orEmpty()
        if (contentType.contains("json", ignoreCase = true)) {
            response.body()
        } else {
            StatusResponse()
        }
    }

    /**
     * GET `/config` — fetches the full `openclaw.json` configuration.
     *
     * Some gateway builds (e.g. `ghcr.io/openclaw/openclaw:latest`) serve an HTML admin UI
     * at `/config` instead of JSON. In that case we return an empty config + null ETag so the
     * editor opens in a clearly-empty (not "disconnected") state. See commit 0c63686.
     *
     * @return Pair of section map and optional ETag header value.
     * @throws GatewayApiException on unexpected HTTP errors.
     */
    suspend fun getConfig(): Pair<Map<String, JsonElement>, String?> = safeRequest(baseUrl) {
        val response = httpClient.get("$baseUrl/config")
        response.mapErrors(baseUrl)
        val contentType = response.headers["Content-Type"].orEmpty()
        if (contentType.contains("json", ignoreCase = true)) {
            val etag = response.headers["ETag"]
            val body: JsonObject = response.body()
            body to etag
        } else {
            emptyMap<String, JsonElement>() to null
        }
    }

    /**
     * PATCH `/config/{section}` — applies a JSON merge-patch to a single config section.
     *
     * @param section Top-level section key (e.g. `"gateway"`).
     * @param value JSON merge-patch value.
     * @param etag Optional ETag for optimistic concurrency — sent as `If-Match` header when non-null.
     * @throws GatewayApiException with `statusCode = 412` on concurrent-edit conflict.
     * @throws ConfigValidationException if the gateway reports a validation failure (422).
     * @throws GatewayApiException on other unexpected HTTP errors.
     */
    suspend fun updateConfig(section: String, value: JsonElement, etag: String?) =
        safeRequest(baseUrl) {
            val url = "$baseUrl/config/$section"
            val response = httpClient.patch(url) {
                contentType(ContentType("application", "merge-patch+json"))
                if (etag != null) header("If-Match", etag)
                setBody(value.toString())
            }
            when (response.status) {
                HttpStatusCode.OK, HttpStatusCode.NoContent -> Unit
                HttpStatusCode.PreconditionFailed ->
                    throw GatewayApiException(url, 412)
                HttpStatusCode.UnprocessableEntity -> {
                    val bodyText = response.bodyAsText()
                    val parsed = runCatching {
                        Json.parseToJsonElement(bodyText).jsonObject
                    }.getOrNull()
                    val field = parsed?.get("field")?.jsonPrimitive?.content
                    val reason = parsed?.get("reason")?.jsonPrimitive?.content
                    if (field != null && reason != null) {
                        throw ConfigValidationException(field, reason)
                    } else {
                        throw GatewayApiException(url, 422, bodyText)
                    }
                }
                else -> response.mapErrors(url)
            }
        }

    /**
     * GET `/api/models/status` — returns all models known to the gateway.
     *
     * @return List of [ModelDto] (may be empty if no providers configured).
     * @throws GatewayAuthException if the endpoint requires authentication.
     * @throws GatewayApiException on unexpected HTTP errors.
     * @throws GatewayUnreachableException if the host cannot be reached.
     * @throws GatewayTimeoutException if the request times out.
     */
    suspend fun getModels(): List<ModelDto> = safeRequest(baseUrl) {
        val url = "$baseUrl/api/models/status"
        val response = httpClient.get(url)
        response.mapErrors(url)
        val contentType = response.headers["Content-Type"].orEmpty()
        if (contentType.contains("json", ignoreCase = true)) {
            response.body()
        } else {
            emptyList()
        }
    }

    /**
     * POST `/api/models/active` — sets the active model on the gateway.
     *
     * @param modelId The `id` of the model to make active.
     * @throws GatewayAuthException if the endpoint requires authentication.
     * @throws GatewayApiException on unexpected HTTP errors (e.g. model not found — 404).
     * @throws GatewayUnreachableException if the host cannot be reached.
     * @throws GatewayTimeoutException if the request times out.
     */
    suspend fun setActiveModel(modelId: String): Unit = safeRequest(baseUrl) {
        val url = "$baseUrl/api/models/active"
        val response = httpClient.post(url) {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("id", modelId) }.toString())
        }
        when (response.status) {
            HttpStatusCode.OK, HttpStatusCode.NoContent -> Unit
            else -> response.mapErrors(url)
        }
    }

    /**
     * POST `/api/ai/recommend` — submits a query to the gateway's AI skill and returns a recommendation.
     *
     * @param request The query and session context.
     * @return [AIRecommendationResponseDto] with the recommendation text and optional suggested changes.
     * @throws GatewayApiException with `statusCode = 404` when the AI skill is not installed.
     * @throws GatewayApiException with `statusCode = 429` when the AI quota is exceeded.
     * @throws GatewayAuthException if the endpoint requires authentication.
     * @throws GatewayUnreachableException if the host cannot be reached.
     * @throws GatewayTimeoutException if the request times out.
     */
    suspend fun getAIRecommendation(request: AIRecommendationRequestDto): AIRecommendationResponseDto =
        safeRequest(baseUrl) {
            val url = "$baseUrl/api/ai/recommend"
            val response = httpClient.post(url) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            when (response.status) {
                HttpStatusCode.OK -> response.body()
                HttpStatusCode.NotFound -> throw GatewayApiException(url, 404)
                HttpStatusCode.TooManyRequests -> throw GatewayApiException(url, 429)
                else -> {
                    response.mapErrors(url)
                    error("unreachable")
                }
            }
        }

    /** Releases underlying HTTP connections. Call on disconnect. */
    fun close() {
        httpClient.close()
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    companion object {

        /** Test-only: create a client backed by a pre-built [HttpClient] (e.g. MockEngine). */
        internal fun forTest(baseUrl: String, httpClient: HttpClient): OpenClawApiClient =
            OpenClawApiClient(baseUrl, httpClient)

        /** Client with no authentication — use for [health] and [probeAuth] probes. */
        fun unauthenticated(
            baseUrl: String,
            allowCleartextPublicIPs: Boolean = false,
        ): OpenClawApiClient =
            OpenClawApiClient(baseUrl, buildHttpClient(token = null, allowCleartextPublicIPs))

        /** Client with Bearer token authentication — use for live sessions. */
        fun authenticated(
            baseUrl: String,
            token: String,
            allowCleartextPublicIPs: Boolean = false,
        ): OpenClawApiClient =
            OpenClawApiClient(baseUrl, buildHttpClient(token = token, allowCleartextPublicIPs))

        private fun buildHttpClient(token: String?, allowCleartextPublicIPs: Boolean): HttpClient =
            HttpClient(OkHttp) {
            engine {
                addInterceptor(CleartextPublicIpInterceptor { allowCleartextPublicIPs })
            }
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                requestTimeoutMillis = 30_000
                socketTimeoutMillis = 30_000
            }
            install(Logging) {
                level = LogLevel.HEADERS
                logger = object : Logger {
                    override fun log(message: String) {
                        android.util.Log.d("OpenClawApiClient", message)
                    }
                }
                // Strip Authorization header — tokens must never appear in logcat
                sanitizeHeader { header -> header == "Authorization" }
            }
            if (token != null) {
                install(Auth) {
                    bearer {
                        loadTokens { BearerTokens(token, "") }
                        sendWithoutRequest { true }
                    }
                }
            }
        }
    }
}

// ── Error mapping ─────────────────────────────────────────────────────────────

private fun io.ktor.client.statement.HttpResponse.mapErrors(url: String) {
    if (status.isSuccess()) return
    when (status) {
        HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden ->
            throw GatewayAuthException(url, status.value)
        else ->
            throw GatewayApiException(url, status.value)
    }
}

private suspend fun <T> safeRequest(url: String, block: suspend () -> T): T {
    return try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: GatewayException) {
        // Re-throw all domain exceptions unchanged — including ConfigValidationException,
        // which would otherwise fall through to the generic catch and become GatewayUnreachableException.
        throw e
    } catch (e: HttpRequestTimeoutException) {
        throw GatewayTimeoutException(url, e)
    } catch (e: SSLException) {
        throw GatewayTlsException(url, e)
    } catch (e: UnknownHostException) {
        throw GatewayUnreachableException(url, e)
    } catch (e: ConnectException) {
        throw GatewayUnreachableException(url, e)
    } catch (e: SerializationException) {
        throw GatewayApiException(url, 0, "Response deserialization failed: ${e.message}")
    } catch (e: Exception) {
        throw GatewayUnreachableException(url, e)
    }
}
