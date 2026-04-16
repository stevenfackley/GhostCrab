package com.openclaw.ghostcrab.data.impl

import com.openclaw.ghostcrab.data.api.OpenClawApiClient
import com.openclaw.ghostcrab.domain.exception.GatewayAuthException
import com.openclaw.ghostcrab.domain.exception.GatewayException
import com.openclaw.ghostcrab.domain.exception.GatewayUnreachableException
import com.openclaw.ghostcrab.domain.model.AuthRequirement
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import com.openclaw.ghostcrab.domain.repository.GatewayConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Production implementation of [GatewayConnectionManager].
 *
 * Thread-safe via [mutex]. The held [OpenClawApiClient] is replaced on each [connect]
 * and released on [disconnect].
 *
 * @param clientFactory Creates [OpenClawApiClient] instances. Injectable for testing.
 */
class GatewayConnectionManagerImpl(
    private val clientFactory: OpenClawApiClientFactory = DefaultClientFactory,
) : GatewayConnectionManager {

    private val _connectionState = MutableStateFlow<GatewayConnection>(GatewayConnection.Disconnected)
    override val connectionState: StateFlow<GatewayConnection> = _connectionState.asStateFlow()

    private var activeClient: OpenClawApiClient? = null
    private val mutex = Mutex()

    override suspend fun probeAuth(url: String): AuthRequirement = withContext(Dispatchers.IO) {
        val probe = clientFactory.unauthenticated(url)
        try {
            probe.health() // throws GatewayUnreachableException if down
            try {
                probe.status()
                AuthRequirement.None
            } catch (e: GatewayAuthException) {
                AuthRequirement.Token
            }
        } finally {
            probe.close()
        }
    }

    override suspend fun connect(url: String, token: String?) = withContext(Dispatchers.IO) {
        mutex.withLock {
            // Disconnect any existing session silently
            activeClient?.close()
            activeClient = null

            _connectionState.value = GatewayConnection.Connecting(url)

            try {
                val authReq = probeAuth(url)
                val client = if (token != null) {
                    clientFactory.authenticated(url, token)
                } else {
                    clientFactory.unauthenticated(url)
                }
                val statusResponse = client.status()
                val isHttps = url.startsWith("https://", ignoreCase = true)

                activeClient = client
                _connectionState.value = GatewayConnection.Connected(
                    url = url,
                    displayName = statusResponse.displayName,
                    version = statusResponse.version,
                    authRequirement = authReq,
                    isHttps = isHttps,
                    capabilities = statusResponse.capabilities,
                    hardwareInfo = statusResponse.hardware,
                )
            } catch (e: GatewayException) {
                activeClient?.close()
                activeClient = null
                _connectionState.value = GatewayConnection.Error(url, e)
                throw e
            }
        }
    }

    override suspend fun disconnect() {
        mutex.withLock {
            activeClient?.close()
            activeClient = null
            _connectionState.value = GatewayConnection.Disconnected
        }
    }

    /** Returns the active client, or null if not connected. Internal use for repositories. */
    fun requireClient(): OpenClawApiClient =
        activeClient ?: error("No active gateway connection")
}

// ── Factory interface for testability ────────────────────────────────────────

interface OpenClawApiClientFactory {
    fun unauthenticated(baseUrl: String): OpenClawApiClient
    fun authenticated(baseUrl: String, token: String): OpenClawApiClient
}

object DefaultClientFactory : OpenClawApiClientFactory {
    override fun unauthenticated(baseUrl: String) = OpenClawApiClient.unauthenticated(baseUrl)
    override fun authenticated(baseUrl: String, token: String) = OpenClawApiClient.authenticated(baseUrl, token)
}
