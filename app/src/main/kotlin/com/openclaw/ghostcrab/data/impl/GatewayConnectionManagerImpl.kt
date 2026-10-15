package com.openclaw.ghostcrab.data.impl

import com.openclaw.ghostcrab.data.api.OpenClawApiClient
import com.openclaw.ghostcrab.data.gateway.ClientInfo
import com.openclaw.ghostcrab.data.gateway.ConnectAuth
import com.openclaw.ghostcrab.data.gateway.DeviceIdentity
import com.openclaw.ghostcrab.data.gateway.DeviceIdentityProvider
import com.openclaw.ghostcrab.data.gateway.DeviceTokenStore
import com.openclaw.ghostcrab.data.gateway.GatewayHandshake
import com.openclaw.ghostcrab.data.gateway.GatewayRpcClient
import com.openclaw.ghostcrab.data.gateway.HelloOk
import com.openclaw.ghostcrab.data.gateway.StoredDeviceToken
import com.openclaw.ghostcrab.data.gateway.gatewayCapabilities
import com.openclaw.ghostcrab.data.gateway.gatewayKeyOf
import com.openclaw.ghostcrab.data.gateway.selectConnectAuth
import com.openclaw.ghostcrab.data.gateway.toAuthRequirement
import com.openclaw.ghostcrab.domain.exception.DeviceCredentialStorageException
import com.openclaw.ghostcrab.domain.exception.GatewayConnectAuthException
import com.openclaw.ghostcrab.domain.exception.GatewayException
import com.openclaw.ghostcrab.domain.exception.GatewayNotConnectedException
import com.openclaw.ghostcrab.domain.exception.GatewayPairingRequiredException
import com.openclaw.ghostcrab.domain.exception.GatewayProtocolException
import com.openclaw.ghostcrab.domain.model.AuthRequirement
import com.openclaw.ghostcrab.domain.model.ConnectionWarning
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import com.openclaw.ghostcrab.domain.repository.GatewayConnectionManager
import com.openclaw.ghostcrab.domain.repository.GatewayHealthChecker
import com.openclaw.ghostcrab.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

private const val METHOD_HEALTH = "health"
private const val DEVICE_TOKEN_MISMATCH = "AUTH_DEVICE_TOKEN_MISMATCH"
private const val DEFAULT_DISPLAY_NAME = "OpenClaw Gateway"
private val CREDENTIAL_MISSING_CODES = setOf("AUTH_TOKEN_MISSING", "AUTH_PASSWORD_MISSING", "AUTH_REQUIRED")

/**
 * Production [GatewayConnectionManager] (ADR-0007): `GET /health`, then one control-plane WebSocket per session
 * opened with a signed `connect`. Repositories call RPCs on [requireSession]. Thread-safe via [mutex].
 *
 * A caller's timeout or cancellation always propagates as cancellation (the state returns to
 * [GatewayConnection.Disconnected]); it is never turned into a gateway error.
 *
 * @param identityProvider This install's Ed25519 identity (one per process: a Koin `single`).
 * @param deviceTokens Per-gateway device tokens from earlier `hello-ok`s.
 * @param clientInfo `connect.params.client`.
 * @param clientFactory Builds the REST client used for `/health` and to open the socket. Injectable for tests.
 * @param settingsRepository Source of the cleartext-to-public-IP opt-in.
 * @param ioDispatcher Blocking work and the socket reader loop; injected via the Koin IoDispatcher qualifier.
 */
@Suppress("TooManyFunctions") // one session lifecycle: connect, probe, health, teardown helpers
class GatewayConnectionManagerImpl(
    private val identityProvider: DeviceIdentityProvider,
    private val deviceTokens: DeviceTokenStore,
    private val clientInfo: ClientInfo,
    private val clientFactory: OpenClawApiClientFactory = DefaultClientFactory,
    private val settingsRepository: SettingsRepository = AlwaysBlockCleartextSettingsRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : GatewayConnectionManager, GatewayHealthChecker {

    /** The live session: REST client (health, teardown), control-plane socket, and the hello that opened it. */
    private class ActiveSession(val client: OpenClawApiClient, val rpc: GatewayRpcClient, val hello: HelloOk)

    private val _connectionState = MutableStateFlow<GatewayConnection>(GatewayConnection.Disconnected)
    override val connectionState: StateFlow<GatewayConnection> = _connectionState.asStateFlow()

    // Written under [mutex] on the IO dispatcher, read lock-free by repositories on any thread.
    @Volatile private var active: ActiveSession? = null
    private val mutex = Mutex()

    /** Live copy of the user's cleartext opt-in; every request's interceptor reads it. */
    private val allowCleartext = AtomicBoolean(false)

    // Fail closed: a settings read error (e.g. DataStore IOException) means "not allowed", never a crash.
    private val cleartextSetting: Flow<Boolean> = settingsRepository.allowCleartextPublicIPs.catch { emit(false) }

    init {
        // App-lifetime singleton: keep the opt-in current so a Settings change applies to the live session.
        CoroutineScope(SupervisorJob() + ioDispatcher).launch { cleartextSetting.collect { allowCleartext.set(it) } }
    }

    /** Reads the current opt-in before building clients, so the first request never races the collector. */
    private suspend fun primeCleartextSetting() {
        allowCleartext.set(cleartextSetting.firstOrNull() ?: false)
    }

    /**
     * ADR-0007 decision 8: `/health`, then a credential-less signed `connect`. `AUTH_TOKEN_MISSING`,
     * `AUTH_PASSWORD_MISSING` or `AUTH_REQUIRED` → [AuthRequirement.Token]; `hello-ok` → its auth mode;
     * `PAIRING_REQUIRED` → [AuthRequirement.None] (only the approval is missing). Stores nothing.
     */
    override suspend fun probeAuth(url: String): AuthRequirement = withContext(ioDispatcher) {
        primeCleartextSetting()
        val client = clientFactory.unauthenticated(url, allowCleartext::get)
        var rpc: GatewayRpcClient? = null
        try {
            client.health()
            val socket = openRpc(client).also { rpc = it }
            GatewayHandshake.perform(socket, clientInfo, ConnectAuth.None, identityProvider.identity())
                .toAuthRequirement(token = null)
        } catch (e: GatewayConnectAuthException) {
            if (e.detailCode in CREDENTIAL_MISSING_CODES) AuthRequirement.Token else throw e
        } catch (ignored: GatewayPairingRequiredException) {
            AuthRequirement.None
        } finally {
            release(client, rpc)
        }
    }

    /** Emits Connecting, then Connected or Error (and rethrows). Cancellation ends in Disconnected. */
    override suspend fun connect(url: String, token: String?): Unit = withContext(ioDispatcher) {
        mutex.withLock {
            closeActive()
            _connectionState.value = GatewayConnection.Connecting(url)
            val key = gatewayKeyOf(url)
            // Held outside the try so every failure path can release what was opened so far.
            var client: OpenClawApiClient? = null
            var rpc: GatewayRpcClient? = null
            try {
                primeCleartextSetting()
                val rest = clientFactory.unauthenticated(url, allowCleartext::get).also { client = it }
                rest.health() // reachability + cleartext policy, before any credential is sent
                val identity = identityProvider.identity()
                val stored = deviceTokens.loadDeviceToken(key)?.takeIf { it.deviceId == identity.deviceId }
                val socket = openRpc(rest).also { rpc = it }
                val hello = GatewayHandshake.perform(socket, clientInfo, selectConnectAuth(token, stored), identity)
                val tokenWarning = rememberDeviceToken(key, identity, hello, stored)
                active = ActiveSession(rest, socket, hello)
                _connectionState.value = connectedState(url, hello, token, warnings(tokenWarning))
            } catch (e: GatewayConnectAuthException) {
                if (e.detailCode == DEVICE_TOKEN_MISMATCH) forgetDeviceToken(key, e)
                fail(url, e, client, rpc)
                throw e
            } catch (e: GatewayException) {
                fail(url, e, client, rpc)
                throw e
            } catch (e: CancellationException) {
                // The caller went away or its own timeout fired: release, end Disconnected, keep it cancellation.
                release(client, rpc)
                _connectionState.value = GatewayConnection.Disconnected
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // Unclassified (a URL the engine rejects, a client bug): not connectivity; never stuck Connecting.
                val detail = e::class.java.simpleName + (e.message?.let { ": $it" } ?: "")
                val wrapped = GatewayProtocolException(url, detail, e)
                fail(url, wrapped, client, rpc)
                throw wrapped
            }
        }
    }

    private suspend fun openRpc(client: OpenClawApiClient): GatewayRpcClient =
        GatewayRpcClient.create(client.openWsSession(), client.webSocketUrl, ioDispatcher)

    /**
     * Stores the device token `hello-ok` issued. A storage failure must not fail a connect that already succeeded:
     * the session works without it, so it becomes a [ConnectionWarning.DeviceTokenNotSaved] instead.
     *
     * @return The warning when the token could not be stored, else `null`.
     */
    private suspend fun rememberDeviceToken(
        key: String,
        identity: DeviceIdentity,
        hello: HelloOk,
        stored: StoredDeviceToken?,
    ): ConnectionWarning? {
        val issued = hello.deviceToken ?: return null
        // Upstream rule: the same token keeps its stored scopes; a new token takes this socket's live scopes.
        val scopes = stored?.takeIf { it.token == issued }?.scopes ?: hello.scopes.toList()
        return try {
            deviceTokens.saveDeviceToken(key, StoredDeviceToken(issued, scopes, hello.role, identity.deviceId))
            null
        } catch (e: DeviceCredentialStorageException) {
            ConnectionWarning.DeviceTokenNotSaved(e)
        }
    }

    /** Forgets a device token the gateway rejected; a storage failure stays attached to [rejection]. */
    private suspend fun forgetDeviceToken(key: String, rejection: GatewayException) {
        try {
            deviceTokens.clearDeviceToken(key)
        } catch (e: DeviceCredentialStorageException) {
            // The rejection is what the caller must see; the stale token is ignored again on the next attempt.
            rejection.addSuppressed(e)
        }
    }

    /** @return [extra] plus [ConnectionWarning.DeviceIdentityRegenerated] when the identity was replaced. */
    private fun warnings(vararg extra: ConnectionWarning?): List<ConnectionWarning> = buildList {
        if (identityProvider.regenerated.value) add(ConnectionWarning.DeviceIdentityRegenerated)
        extra.filterNotNullTo(this)
    }

    private suspend fun release(client: OpenClawApiClient?, rpc: GatewayRpcClient?) {
        withContext(NonCancellable) { rpc?.close() }
        client?.close()
    }

    private suspend fun closeActive() {
        active?.let { release(it.client, it.rpc) }
        active = null
    }

    private suspend fun fail(url: String, cause: GatewayException, client: OpenClawApiClient?, rpc: GatewayRpcClient?) {
        release(client, rpc)
        closeActive()
        _connectionState.value = GatewayConnection.Error(url, cause, warnings())
    }

    override suspend fun disconnect() {
        mutex.withLock {
            closeActive()
            _connectionState.value = GatewayConnection.Disconnected
        }
    }

    /** One `health` RPC on the live socket (see [GatewayHealthChecker]); fails as soon as the socket dropped. */
    override suspend fun check() {
        requireSession().request(METHOD_HEALTH)
    }

    /**
     * @return The live session's socket, for repository implementations.
     * @throws GatewayNotConnectedException if no session is active.
     */
    fun requireSession(): GatewayRpcClient = active?.rpc ?: throw GatewayNotConnectedException()

    /** @return `hello-ok.auth.scopes` of the live session, or `null` while disconnected. */
    fun grantedScopes(): Set<String>? = active?.hello?.scopes

    /**
     * @return The live session's REST client (only `/health` is real; removed in Task 24).
     * @throws GatewayNotConnectedException if no session is active.
     */
    fun requireClient(): OpenClawApiClient = active?.client ?: throw GatewayNotConnectedException()
}

private fun connectedState(
    url: String,
    hello: HelloOk,
    token: String?,
    warnings: List<ConnectionWarning>,
) = GatewayConnection.Connected(
    url = url,
    displayName = hello.gatewayHost ?: DEFAULT_DISPLAY_NAME,
    version = hello.serverVersion,
    authRequirement = hello.toAuthRequirement(token),
    isHttps = url.startsWith("https://", ignoreCase = true),
    capabilities = gatewayCapabilities(hello.methods),
    hardwareInfo = null,
    tokenOrNull = token,
    warnings = warnings,
)

// ── Fail-safe default settings (no cleartext to public IPs) ──────────────────

private object AlwaysBlockCleartextSettingsRepository : SettingsRepository {
    override val allowCleartextPublicIPs = flowOf(false)
    override suspend fun setAllowCleartextPublicIPs(enabled: Boolean) = Unit
}

// ── Factory interface for testability ────────────────────────────────────────

interface OpenClawApiClientFactory {
    fun unauthenticated(baseUrl: String, allowCleartextPublicIPs: () -> Boolean = { false }): OpenClawApiClient
    fun authenticated(
        baseUrl: String,
        token: String,
        allowCleartextPublicIPs: () -> Boolean = { false },
    ): OpenClawApiClient
}

object DefaultClientFactory : OpenClawApiClientFactory {
    override fun unauthenticated(baseUrl: String, allowCleartextPublicIPs: () -> Boolean) =
        OpenClawApiClient.unauthenticated(baseUrl, allowCleartextPublicIPs)
    override fun authenticated(baseUrl: String, token: String, allowCleartextPublicIPs: () -> Boolean) =
        OpenClawApiClient.authenticated(baseUrl, token, allowCleartextPublicIPs)
}
