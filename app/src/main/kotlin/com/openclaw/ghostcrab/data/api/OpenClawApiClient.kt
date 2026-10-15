package com.openclaw.ghostcrab.data.api

import com.openclaw.ghostcrab.BuildConfig
import com.openclaw.ghostcrab.data.api.dto.HealthResponse
import com.openclaw.ghostcrab.data.ws.KtorWsSession
import com.openclaw.ghostcrab.data.ws.WsSession
import com.openclaw.ghostcrab.domain.exception.CleartextBlockedException
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
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.ContentConvertException
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

/**
 * Ktor client for the gateway's HTTP probe (`GET /health`) and for opening the control-plane WebSocket
 * (ADR-0007). It carries no credentials: authentication happens in the WS `connect` frame, so no token is ever
 * sent over HTTP.
 *
 * Create via [unauthenticated]; call [close] when the session ends to release underlying connections.
 *
 * Timeouts: [CONNECT_TIMEOUT_MS], [REQUEST_TIMEOUT_MS], [SOCKET_TIMEOUT_MS].
 */
class OpenClawApiClient private constructor(
    val baseUrl: String,
    /** Underlying Ktor client: cleartext interceptor, timeouts, sanitized logging. */
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
         * The only kind of client: no credentials (ADR-0007). Use for [health] and [openWsSession].
         *
         * @param baseUrl Gateway base URL (`http(s)://host:port`).
         * @param allowCleartextPublicIPs Read on every request, so a Settings change applies immediately.
         * @return A new client; the caller must [close] it.
         */
        fun unauthenticated(
            baseUrl: String,
            allowCleartextPublicIPs: () -> Boolean = { false },
        ): OpenClawApiClient = OpenClawApiClient(baseUrl, buildHttpClient(allowCleartextPublicIPs))

        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val REQUEST_TIMEOUT_MS = 30_000L
        private const val SOCKET_TIMEOUT_MS = 30_000L

        private fun buildHttpClient(allowCleartextPublicIPs: () -> Boolean): HttpClient = HttpClient(OkHttp) {
            engine {
                addInterceptor(CleartextPublicIpInterceptor(allowCleartextPublicIPs))
            }
            install(WebSockets)
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
                    // Defence in depth: no request carries Authorization any more, but never log one.
                    sanitizeHeader { header -> header == HttpHeaders.Authorization }
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
        // Domain exceptions pass through unchanged.
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
