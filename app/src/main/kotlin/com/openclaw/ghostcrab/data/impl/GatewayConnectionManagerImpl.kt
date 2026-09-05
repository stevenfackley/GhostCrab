package com.openclaw.ghostcrab.data.impl

import com.openclaw.ghostcrab.data.api.OpenClawApiClient
import com.openclaw.ghostcrab.domain.exception.GatewayAuthException
import com.openclaw.ghostcrab.domain.exception.GatewayException
import com.openclaw.ghostcrab.domain.exception.GatewayUnreachableException
import com.openclaw.ghostcrab.domain.model.AuthRequirement
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import com.openclaw.ghostcrab.domain.repository.GatewayConnectionManager
import com.openclaw.ghostcrab.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
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
    private val settingsRepository: SettingsRepository = AlwaysBlockCleartextSettingsRepository,
) : GatewayConnectionManager {

    private val _connectionState = MutableStateFlow<GatewayConnection>(GatewayConnection.Disconnected)
    override val connectionState: StateFlow<GatewayConnection> = _connectionState.asStateFlow()

    private var activeClient: OpenClawApiClient? = null
    private val mutex = Mutex()

    override suspend fun probeAuth(url: String): AuthRequirement = withContext(Dispatchers.IO) {
        val allowCleartext = settingsRepository.allowCleartextPublicIPs.firstOrNull() ?: false
        val probe = clientFactory.unauthenticated(url, allowCleartext)
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

            // Held outside the try so every failure path can release the half-built client;
            // it only becomes activeClient once status() has succeeded.
            var client: OpenClawApiClient? = null
            try {
                val allowCleartext = settingsRepository.allowCleartextPublicIPs.firstOrNull() ?: false
                val authReq = probeAuth(url)
                client = if (token != null) {
                    clientFactory.authenticated(url, token, allowCleartext)
                } else {
                    clientFactory.unauthenticated(url, allowCleartext)
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
                    tokenOrNull = token,
                )
            } catch (e: GatewayException) {
                failConnect(url, e, client)
                throw e
            } catch (e: CancellationException) {
                // Caller went away mid-handshake: release the half-built client and return to
                // the terminal state instead of leaving the UI on Connecting forever.
                releaseClients(client)
                _connectionState.value = GatewayConnection.Disconnected
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // Anything the client layer did not classify (e.g. a URL the engine rejects)
                // must still land in Error, never a stuck Connecting.
                val wrapped = GatewayUnreachableException(url, e)
                failConnect(url, wrapped, client)
                throw wrapped
            }
        }
    }

    /** Closes [pending] (a client whose handshake failed) and whatever is currently active. */
    private fun releaseClients(pending: OpenClawApiClient?) {
        pending?.close()
        activeClient?.close()
        activeClient = null
    }

    private fun failConnect(url: String, cause: GatewayException, pending: OpenClawApiClient?) {
        releaseClients(pending)
        _connectionState.value = GatewayConnection.Error(url, cause)
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

// ── Fail-safe default settings (no cleartext to public IPs) ──────────────────

private object AlwaysBlockCleartextSettingsRepository : com.openclaw.ghostcrab.domain.repository.SettingsRepository {
    override val allowCleartextPublicIPs = kotlinx.coroutines.flow.flowOf(false)
    override suspend fun setAllowCleartextPublicIPs(enabled: Boolean) = Unit
}

// ── Factory interface for testability ────────────────────────────────────────

interface OpenClawApiClientFactory {
    fun unauthenticated(baseUrl: String, allowCleartextPublicIPs: Boolean = false): OpenClawApiClient
    fun authenticated(baseUrl: String, token: String, allowCleartextPublicIPs: Boolean = false): OpenClawApiClient
}

object DefaultClientFactory : OpenClawApiClientFactory {
    override fun unauthenticated(baseUrl: String, allowCleartextPublicIPs: Boolean) =
        OpenClawApiClient.unauthenticated(baseUrl, allowCleartextPublicIPs)
    override fun authenticated(baseUrl: String, token: String, allowCleartextPublicIPs: Boolean) =
        OpenClawApiClient.authenticated(baseUrl, token, allowCleartextPublicIPs)
}
