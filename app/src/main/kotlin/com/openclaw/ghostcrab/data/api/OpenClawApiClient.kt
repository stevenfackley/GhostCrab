package com.openclaw.ghostcrab.data.api

import com.openclaw.ghostcrab.data.api.dto.AIRecommendationRequestDto
import com.openclaw.ghostcrab.data.api.dto.AIRecommendationResponseDto
import com.openclaw.ghostcrab.data.api.dto.HealthResponse
import com.openclaw.ghostcrab.data.api.dto.ModelDto
import com.openclaw.ghostcrab.data.api.dto.StatusResponse
import com.openclaw.ghostcrab.data.ws.GatewayWsClient
import com.openclaw.ghostcrab.data.ws.KtorWsSession
import com.openclaw.ghostcrab.data.ws.WsSession
import com.openclaw.ghostcrab.domain.exception.CleartextBlockedException
import com.openclaw.ghostcrab.domain.exception.ConfigValidationException
import com.openclaw.ghostcrab.domain.exception.GatewayApiException
import com.openclaw.ghostcrab.domain.exception.GatewayAuthException
import com.openclaw.ghostcrab.domain.exception.GatewayException
import com.openclaw.ghostcrab.domain.exception.GatewayProtocolException
import com.openclaw.ghostcrab.domain.exception.GatewayTimeoutException
import com.openclaw.ghostcrab.domain.exception.GatewayTlsException
import com.openclaw.ghostcrab.domain.exception.GatewayUnreachableException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import com.openclaw.ghostcrab.BuildConfig
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSecure
import io.ktor.http.isSuccess
import io.ktor.serialization.ContentConvertException
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
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

/**
 * Ktor-backed HTTP client for the OpenClaw Gateway API.
 *
 * Create via [unauthenticated] for probing or [authenticated] for a live session.
 * Call [close] when the session ends to release underlying connections.
 *
 * **Authorization headers are never logged.** Verify with `adb logcat | grep -i bearer`.
 *
 * Timeouts: [CONNECT_TIMEOUT_MS], [REQUEST_TIMEOUT_MS], [SOCKET_TIMEOUT_MS].
 */
class OpenClawApiClient private constructor(
    val baseUrl: String,
    /** Underlying Ktor client: cleartext interceptor, timeouts, sanitized logging, origin-scoped bearer. */
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
        if (response.isJson()) response.body() else StatusResponse()
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
        if (response.isJson()) {
            val etag = response.headers[HttpHeaders.ETag]
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
     * @return The ETag the gateway returned with the write, or null when it sent none.
     * @throws GatewayApiException with `statusCode = 412` on concurrent-edit conflict.
     * @throws ConfigValidationException if the gateway reports a validation failure (422).
     * @throws GatewayApiException on other unexpected HTTP errors.
     */
    suspend fun updateConfig(section: String, value: JsonElement, etag: String?): String? =
        safeRequest(baseUrl) {
            val url = "$baseUrl/config/$section"
            val response = httpClient.patch(url) {
                contentType(ContentType("application", "merge-patch+json"))
                if (etag != null) header("If-Match", etag)
                setBody(value.toString())
            }
            when (response.status) {
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
                else -> {
                    response.mapErrors(url)
                    response.headers[HttpHeaders.ETag]
                }
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
        if (response.isJson()) response.body() else emptyList()
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
                HttpStatusCode.NotFound -> throw GatewayApiException(url, 404)
                HttpStatusCode.TooManyRequests -> throw GatewayApiException(url, 429)
                else -> {
                    response.mapErrors(url)
                    response.body()
                }
            }
        }

    /**
     * Opens the gateway's JSON-RPC WebSocket (`<baseUrl>/ws`) on this client, so the upgrade request
     * carries the same origin-scoped bearer, cleartext policy, timeouts and sanitized logging as
     * every HTTP call — no hand-added `Authorization` header.
     *
     * @return A connected [GatewayWsClient]; the caller must [GatewayWsClient.close] it.
     * @throws GatewayUnreachableException if the host cannot be reached.
     * @throws GatewayTimeoutException if the upgrade times out.
     * @throws CleartextBlockedException if app policy blocks cleartext to a public IP.
     * @throws GatewayProtocolException if the server refuses the upgrade.
     */
    suspend fun openWebSocket(): GatewayWsClient = safeRequest(baseUrl) {
        GatewayWsClient.create(KtorWsSession(httpClient.webSocketSession(webSocketUrl)), webSocketUrl)
    }

    /** `ws(s)://host:port/ws` for [baseUrl] (`https` becomes `wss`): the gateway control-plane endpoint. */
    val webSocketUrl: String
        get() = baseUrl.replaceFirst(Regex("^http"), "ws").trimEnd('/') + "/ws"

    /**
     * Opens the gateway control-plane WebSocket ([webSocketUrl], ADR-0007) on this client's engine, so the
     * upgrade honours the cleartext policy, timeouts and sanitized logging of every HTTP call. The upgrade
     * carries no credential: authentication happens in the `connect` frame.
     *
     * @return The raw socket. Wrap it in a `GatewayRpcClient`, which then owns and closes it.
     * @throws GatewayUnreachableException if the host cannot be reached.
     * @throws GatewayTimeoutException if the upgrade times out.
     * @throws CleartextBlockedException if app policy blocks cleartext to a public IP.
     * @throws GatewayProtocolException if the server refuses the upgrade.
     */
    suspend fun openWsSession(): WsSession = safeRequest(baseUrl) {
        KtorWsSession(httpClient.webSocketSession(webSocketUrl))
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

        /**
         * Client with no authentication — use for [health] and [probeAuth] probes.
         *
         * @param allowCleartextPublicIPs Read on every request, so a Settings change applies immediately.
         */
        fun unauthenticated(
            baseUrl: String,
            allowCleartextPublicIPs: () -> Boolean = { false },
        ): OpenClawApiClient =
            OpenClawApiClient(baseUrl, buildHttpClient(baseUrl, token = null, allowCleartextPublicIPs))

        /**
         * Client with Bearer token authentication — use for live sessions.
         *
         * @param allowCleartextPublicIPs Read on every request, so a Settings change applies immediately.
         */
        fun authenticated(
            baseUrl: String,
            token: String,
            allowCleartextPublicIPs: () -> Boolean = { false },
        ): OpenClawApiClient =
            OpenClawApiClient(baseUrl, buildHttpClient(baseUrl, token = token, allowCleartextPublicIPs))

        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val REQUEST_TIMEOUT_MS = 30_000L
        private const val SOCKET_TIMEOUT_MS = 30_000L

        private fun buildHttpClient(
            baseUrl: String,
            token: String?,
            allowCleartextPublicIPs: () -> Boolean,
        ): HttpClient = HttpClient(OkHttp) {
            engine {
                addInterceptor(CleartextPublicIpInterceptor(allowCleartextPublicIPs))
            }
            install(io.ktor.client.plugins.websocket.WebSockets)
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
            install(HttpTimeout) {
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
                socketTimeoutMillis = SOCKET_TIMEOUT_MS
            }
            // Header-level request logging is a debug-only diagnostic. Release builds install
            // no logger at all (and R8 strips Log.d call sites — see proguard-rules.pro).
            if (BuildConfig.DEBUG) {
                install(Logging) {
                    level = LogLevel.HEADERS
                    logger = object : Logger {
                        override fun log(message: String) {
                            android.util.Log.d("OpenClawApiClient", message)
                        }
                    }
                    // Strip Authorization header — tokens must never appear in logcat
                    sanitizeHeader { header -> header == HttpHeaders.Authorization }
                }
            }
            if (token != null) {
                install(scopedBearerPlugin(origin = Url(baseUrl), token = token))
            }
        }

        /**
         * Attaches `Authorization: Bearer` only to requests bound for [origin].
         *
         * A plain request hook rather than Ktor's `Auth` plugin: that plugin also retries on a
         * 401 challenge, which would hand the token to any host that answers a redirected
         * request with `WWW-Authenticate: Bearer`. This hook has no retry path.
         */
        private fun scopedBearerPlugin(origin: Url, token: String) =
            createClientPlugin("ScopedBearer") {
                onRequest { request, _ ->
                    if (isSameOrigin(origin, request.url.build())) {
                        request.headers[HttpHeaders.Authorization] = "Bearer $token"
                    }
                }
            }

        /**
         * `true` when [candidate] targets the same host and port as [origin] and does not
         * downgrade a TLS origin to cleartext. `ws`/`wss` count as `http`/`https`.
         *
         * @param origin The gateway base URL the token was issued for.
         * @param candidate The URL of an outgoing request.
         */
        internal fun isSameOrigin(origin: Url, candidate: Url): Boolean {
            val sameHostAndPort =
                candidate.host.equals(origin.host, ignoreCase = true) && candidate.port == origin.port
            val noTlsDowngrade = !origin.protocol.isSecure() || candidate.protocol.isSecure()
            return sameHostAndPort && noTlsDowngrade
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
        // Domain exceptions (incl. ConfigValidationException) pass through unchanged.
        throw e
    } catch (e: HttpRequestTimeoutException) {
        throw GatewayTimeoutException(url, e)
    } catch (e: ConnectTimeoutException) {
        throw GatewayTimeoutException(url, e)
    } catch (e: SocketTimeoutException) {
        throw GatewayTimeoutException(url, e)
    } catch (e: SSLException) {
        throw GatewayTlsException(url, e)
    } catch (e: CleartextBlockedIOException) {
        throw CleartextBlockedException(url, e.host, e)
    } catch (e: IOException) {
        // DNS failure, refused/reset connection, no route: the host is unreachable.
        throw GatewayUnreachableException(url, e)
    } catch (e: SerializationException) {
        throw GatewayProtocolException(url, "response deserialization failed: ${e.message}", e)
    } catch (e: ContentConvertException) {
        throw GatewayProtocolException(url, "response deserialization failed: ${e.message}", e)
    } catch (e: Exception) {
        // Anything else is a client/protocol bug, not a connectivity problem — never call it "unreachable".
        throw GatewayProtocolException(url, e::class.java.simpleName + (e.message?.let { ": $it" } ?: ""), e)
    }
}

/** `true` when the gateway answered with a JSON content type (upstream serves HTML at /status and /config). */
private fun io.ktor.client.statement.HttpResponse.isJson(): Boolean =
    headers[HttpHeaders.ContentType].orEmpty().contains("json", ignoreCase = true)
