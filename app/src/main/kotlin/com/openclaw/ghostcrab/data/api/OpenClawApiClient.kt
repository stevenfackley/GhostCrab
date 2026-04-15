package com.openclaw.ghostcrab.data.api

import com.openclaw.ghostcrab.data.api.dto.HealthResponse
import com.openclaw.ghostcrab.data.api.dto.StatusResponse
import com.openclaw.ghostcrab.domain.exception.GatewayApiException
import com.openclaw.ghostcrab.domain.exception.GatewayAuthException
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
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
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
     * @throws GatewayAuthException if auth is required and no/invalid token is present.
     * @throws GatewayUnreachableException if the host cannot be reached.
     * @throws GatewayTimeoutException if the request times out.
     */
    suspend fun status(): StatusResponse = safeRequest(baseUrl) {
        httpClient.get("$baseUrl/status").let { response ->
            response.mapErrors(baseUrl)
            response.body()
        }
    }

    /** Releases underlying HTTP connections. Call on disconnect. */
    fun close() {
        httpClient.close()
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    companion object {

        /** Client with no authentication — use for [health] and [probeAuth] probes. */
        fun unauthenticated(baseUrl: String): OpenClawApiClient =
            OpenClawApiClient(baseUrl, buildHttpClient(token = null))

        /** Client with Bearer token authentication — use for live sessions. */
        fun authenticated(baseUrl: String, token: String): OpenClawApiClient =
            OpenClawApiClient(baseUrl, buildHttpClient(token = token))

        private fun buildHttpClient(token: String?): HttpClient = HttpClient(OkHttp) {
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
    } catch (e: GatewayAuthException) {
        throw e
    } catch (e: GatewayApiException) {
        throw e
    } catch (e: HttpRequestTimeoutException) {
        throw GatewayTimeoutException(url, e)
    } catch (e: SSLException) {
        throw GatewayTlsException(url, e)
    } catch (e: UnknownHostException) {
        throw GatewayUnreachableException(url, e)
    } catch (e: ConnectException) {
        throw GatewayUnreachableException(url, e)
    } catch (e: Exception) {
        throw GatewayUnreachableException(url, e)
    }
}
