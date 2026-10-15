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
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

private const val METHOD_HEALTH = "health"
private const val DEVICE_TOKEN_MISMATCH = "AUTH_DEVICE_TOKEN_MISMATCH"
private const val DEFAULT_DISPLAY_NAME = "OpenClaw Gateway"
private val CREDENTIAL_MISSING_CODES = setOf("AUTH_TOKEN_MISSING", "AUTH_PASSWORD_MISSING", "AUTH_REQUIRED")
private const val NANOS_PER_MILLI = 1_000_000L

/**
 * How long a reconnected session must stay live before losing its socket may trigger another silent reconnect.
 * Shorter-lived sessions end in Error instead, so a gateway that keeps dropping the socket never loops.
 */
internal const val RECONNECT_REARM_MS: Long = 5 * 60_000L

/**
 * Production [GatewayConnectionManager] (ADR-0007): `GET /health`, then one control-plane WebSocket per session
 * opened with a signed `connect`. Repositories call RPCs on [requireSession]. Thread-safe via [mutex].
 *
 * The live socket is watched: when it ends (gateway restart, Wi-Fi change, Doze, a 1008 close) the manager makes
 * one silent reconnect with this gateway's stored device token (the shared token when none is stored). Meanwhile
 * the state is [GatewayConnection.Connected] with `reconnecting = true` and [requireSession] fails with a retryable
 * [GatewayNotConnectedException] naming the URL; a successful reconnect publishes a new Connected (next
 * `sessionGeneration`). If the reconnect fails, or the reconnected socket ends within [RECONNECT_REARM_MS], the
 * state becomes [GatewayConnection.Error]: with the gateway's refusal (pairing required, rejected credential) when
 * the reconnect got one, else with the typed end cause. A reconnected session that stayed live that long earns
 * another silent reconnect, so a second blip hours later is covered too. A reconnect stopped by [connect] or
 * [disconnect] ends Disconnected.
 *
 * A caller's timeout or cancellation always propagates as cancellation (the state returns to
 * [GatewayConnection.Disconnected]); it is never turned into a gateway error.
 *
 * @param identityProvider This install's Ed25519 identity (one per process: a Koin `single`).
 * @param deviceTokens Per-gateway device tokens from earlier `hello-ok`s.
 * @param clientInfo `connect.params.client`.
 * @param clientFactory Builds the REST client used for `/health` and to open the socket. Injectable for tests.
 * @param settingsRepository Source of the cleartext-to-public-IP opt-in.
 * @param ioDispatcher Blocking work, the socket reader loop and the socket watcher; injected via the Koin
 *   IoDispatcher qualifier.
 * @param elapsedRealtimeMs Monotonic milliseconds that keep counting in deep sleep (production passes
 *   `SystemClock::elapsedRealtime`, so Doze counts toward [RECONNECT_REARM_MS]); tests pass virtual time.
 */
// One session lifecycle (connect, probe, health, watch, reconnect, teardown helpers); collaborators plus two test
// seams (dispatcher, clock).
@Suppress("TooManyFunctions", "LongParameterList")
class GatewayConnectionManagerImpl(
    private val identityProvider: DeviceIdentityProvider,
    private val deviceTokens: DeviceTokenStore,
    private val clientInfo: ClientInfo,
    private val clientFactory: OpenClawApiClientFactory = DefaultClientFactory,
    private val settingsRepository: SettingsRepository = AlwaysBlockCleartextSettingsRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val elapsedRealtimeMs: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
) : GatewayConnectionManager, GatewayHealthChecker {

    /** What a connect attempt has opened so far, so every failure path can release it. */
    private class Opening {
        var client: OpenClawApiClient? = null
        var rpc: GatewayRpcClient? = null
    }

    private val _connectionState = MutableStateFlow<GatewayConnection>(GatewayConnection.Disconnected)
    override val connectionState: StateFlow<GatewayConnection> = _connectionState.asStateFlow()

    // Written under [mutex] on the IO dispatcher, read lock-free by repositories on any thread.
    @Volatile private var active: ActiveSession? = null
    private val mutex = Mutex()

    /** App-lifetime work: the cleartext-setting collector and the socket watchers. */
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    /** Watches [active]'s socket; [connect] and [disconnect] cancel it before they take [mutex]. */
    @Volatile private var watcher: Job? = null

    /** The URL of the silent reconnect in progress, else `null`; read lock-free by [requireSession]. */
    @Volatile private var reconnectingUrl: String? = null

    /** [GatewayConnection.Connected.sessionGeneration] of the latest session made live; guarded by [mutex]. */
    private var generation = 0L

    /** Gateways that accepted this run's regenerated identity (a `hello-ok`); guarded by [mutex]. */
    private val reapprovedGateways = mutableSetOf<String>()

    /** Live copy of the user's cleartext opt-in; every request's interceptor reads it. */
    private val allowCleartext = AtomicBoolean(false)

    // Fail closed: a settings read error (e.g. DataStore IOException) means "not allowed", never a crash.
    private val cleartextSetting: Flow<Boolean> = settingsRepository.allowCleartextPublicIPs.catch { emit(false) }

    init {
        // App-lifetime singleton: keep the opt-in current so a Settings change applies to the live session.
        scope.launch { cleartextSetting.collect { allowCleartext.set(it) } }
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
            // A probe socket's close failure changes nothing about the answer; both sides are closed regardless.
            release(client, rpc)
        }
    }

    /**
     * Emits Connecting, then Connected or Error (and rethrows). Cancellation ends in Disconnected. Replaces any live
     * session (its watcher is stopped first, so a silent reconnect in progress cannot delay this connect).
     *
     * @throws GatewayPairingRequiredException while this device awaits approval on the gateway.
     * @throws GatewayConnectAuthException if the gateway rejects the credential or the device proof.
     * @throws DeviceCredentialStorageException if the device identity cannot be read or created.
     * @throws GatewayException subtype for transport or protocol failures.
     */
    override suspend fun connect(url: String, token: String?): Unit = withContext(ioDispatcher) {
        lockedAfterStoppingWatcher {
            _connectionState.value = GatewayConnection.Connecting(url)
            val opening = Opening()
            try {
                closeActive()
                activate(openSession(url, token, opening, reconnecting = false))
            } catch (e: GatewayConnectAuthException) {
                forgetRevokedDeviceToken(url, e)
                fail(url, e, opening)
                throw e
            } catch (e: GatewayException) {
                fail(url, e, opening)
                throw e
            } catch (e: CancellationException) {
                // The caller went away or its own timeout fired: release, end Disconnected, keep it cancellation.
                release(opening.client, opening.rpc)?.let(e::addSuppressed)
                _connectionState.value = GatewayConnection.Disconnected
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // Unclassified (a URL the engine rejects, a client bug): not connectivity; never stuck Connecting.
                val detail = e::class.java.simpleName + (e.message?.let { ": $it" } ?: "")
                val wrapped = GatewayProtocolException(url, detail, e)
                fail(url, wrapped, opening)
                throw wrapped
            }
        }
    }

    /**
     * Stops the socket watcher, then runs [body] under [mutex]. When the caller is cancelled in between, the
     * session the watcher guarded is live but no longer watched; it is ended under [NonCancellable] (see
     * [endUnwatchedSession]) so a dead socket can never go unnoticed.
     */
    private suspend fun lockedAfterStoppingWatcher(body: suspend () -> Unit) {
        var bodyRan = false
        try {
            watcher?.cancelAndJoin()
            mutex.withLock {
                bodyRan = true
                body()
            }
        } finally {
            if (!bodyRan) withContext(NonCancellable) { endUnwatchedSession() }
        }
    }

    /**
     * A [connect] whose caller was cancelled between stopping the watcher and its lock body: the session it meant
     * to replace is live but no longer watched, so a dead socket would never be noticed. Ends it (Disconnected),
     * unless another connect has made a session live and watched meanwhile.
     */
    private suspend fun endUnwatchedSession() = mutex.withLock {
        if (active != null && watcher?.isActive != true) {
            closeActive()
            _connectionState.value = GatewayConnection.Disconnected
        }
    }

    /**
     * `/health`, socket, signed `connect`, device-token bookkeeping. Under [mutex].
     *
     * @param opening Records what was opened, for the caller to release on failure.
     * @param reconnecting `true` for the silent reconnect: the stored device token goes first, the shared token is
     *   the fallback; such a session is not reconnected again until it has been live for [RECONNECT_REARM_MS].
     * @return The session, not yet [activate]d.
     */
    private suspend fun openSession(
        url: String,
        token: String?,
        opening: Opening,
        reconnecting: Boolean,
    ): ActiveSession {
        primeCleartextSetting()
        val rest = clientFactory.unauthenticated(url, allowCleartext::get).also { opening.client = it }
        rest.health() // reachability + cleartext policy, before any credential is sent
        val key = gatewayKeyOf(url)
        val identity = identityProvider.identity()
        val stored = deviceTokens.loadDeviceToken(key)?.takeIf { it.deviceId == identity.deviceId }
        val socket = openRpc(rest).also { opening.rpc = it }
        val sharedToken = token.takeUnless { reconnecting && stored != null }
        val auth = selectConnectAuth(sharedToken, stored)
        val hello = GatewayHandshake.perform(socket, clientInfo, auth, identity)
        if (identityProvider.regenerated.value) reapprovedGateways += key
        val tokenWarning = rememberDeviceToken(key, identity, hello, stored)
        return ActiveSession(
            url = url,
            token = token,
            client = rest,
            rpc = socket,
            hello = hello,
            reconnected = reconnecting,
            liveSinceMs = elapsedRealtimeMs(),
            warnings = warnings(key, tokenWarning),
        )
    }

    /**
     * Makes [session] the live one, publishes Connected (a new [GatewayConnection.Connected.sessionGeneration], so
     * a reconnected session is a new value) and starts watching its socket. Under [mutex].
     */
    private fun activate(session: ActiveSession) {
        active = session
        _connectionState.value = connectedState(session, ++generation)
        watcher = scope.launch { watch(session) }
    }

    /** Waits for [session]'s socket to end; acts only if it is still the live session (not closed on purpose). */
    private suspend fun watch(session: ActiveSession) {
        val cause = session.rpc.awaitClosed()
        mutex.withLock { if (active === session) onSocketLost(session, cause) }
    }

    /**
     * Under [mutex]: the live socket ended with [cause]. One silent reconnect (published as Connected with
     * `reconnecting = true`), else Error. When [connect] or [disconnect] stops the reconnect, the state is set to
     * Disconnected here, before the cancellation propagates: their caller may be cancelled before its own lock body
     * runs, and the state must never stay Connected without a session.
     */
    private suspend fun onSocketLost(lost: ActiveSession, cause: GatewayException) {
        val mayReconnect = lost.mayReconnect(nowMs = elapsedRealtimeMs())
        // Set before [active] is cleared, so [requireSession] never reports a plain disconnect mid-reconnect.
        if (mayReconnect) reconnectingUrl = lost.url
        active = null
        try {
            release(lost.client, lost.rpc)?.let(cause::addSuppressed)
            val reported = if (mayReconnect) {
                _connectionState.update { (it as? GatewayConnection.Connected)?.copy(reconnecting = true) ?: it }
                reconnectQuietly(lost, cause) ?: return
            } else {
                cause
            }
            _connectionState.value = GatewayConnection.Error(lost.url, reported, warnings(gatewayKeyOf(lost.url)))
        } catch (e: CancellationException) {
            _connectionState.value = GatewayConnection.Disconnected
            throw e
        } finally {
            reconnectingUrl = null
        }
    }

    /**
     * The single silent reconnect; a failure ends the attempt and is never retried here.
     *
     * @return `null` when the reconnected session is live, else the error to report. The gateway's own refusal
     *   (pairing required, a rejected credential or device) is the actionable one, so it becomes the primary cause
     *   with [cause] attached; any other failure (transport, credential storage) is attached to [cause] instead.
     */
    private suspend fun reconnectQuietly(lost: ActiveSession, cause: GatewayException): GatewayException? {
        val opening = Opening()
        return try {
            activate(openSession(lost.url, lost.token, opening, reconnecting = true))
            null
        } catch (e: CancellationException) {
            release(opening.client, opening.rpc)?.let(e::addSuppressed)
            throw e
        } catch (e: GatewayPairingRequiredException) {
            abandonReconnect(primary = e, secondary = cause, opening)
        } catch (e: GatewayConnectAuthException) {
            forgetRevokedDeviceToken(lost.url, e)
            abandonReconnect(primary = e, secondary = cause, opening)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            abandonReconnect(primary = cause, secondary = e, opening)
        }
    }

    /**
     * Releases what the failed reconnect opened.
     *
     * @return [primary], with [secondary] attached.
     */
    private suspend fun abandonReconnect(
        primary: GatewayException,
        secondary: Exception,
        opening: Opening,
    ): GatewayException {
        release(opening.client, opening.rpc)?.let(primary::addSuppressed)
        primary.addSuppressed(secondary)
        return primary
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

    /** Forgets a device token the gateway revoked; a storage failure stays attached to [rejection]. */
    private suspend fun forgetRevokedDeviceToken(url: String, rejection: GatewayConnectAuthException) {
        if (rejection.detailCode != DEVICE_TOKEN_MISMATCH) return
        try {
            deviceTokens.clearDeviceToken(gatewayKeyOf(url))
        } catch (e: DeviceCredentialStorageException) {
            // The rejection is what the caller must see; the stale token is ignored again on the next attempt.
            rejection.addSuppressed(e)
        }
    }

    /**
     * @return [extra] plus [ConnectionWarning.DeviceIdentityRegenerated] while the identity was replaced in this run
     *   and the gateway behind [key] has not accepted the new one yet.
     */
    private fun warnings(key: String, vararg extra: ConnectionWarning?): List<ConnectionWarning> = buildList {
        if (identityProvider.regenerated.value && key !in reapprovedGateways) {
            add(ConnectionWarning.DeviceIdentityRegenerated)
        }
        extra.filterNotNullTo(this)
    }

    /**
     * Closes the socket (under [NonCancellable]) and the REST client, each even when the other fails. Never throws.
     *
     * @return The close failure, for the caller to attach to the error it reports, or `null`. Callers that end a
     *   session on purpose (disconnect, replacing it, the auth probe) have no error to attach it to and ignore it:
     *   both sides are closed either way.
     */
    private suspend fun release(client: OpenClawApiClient?, rpc: GatewayRpcClient?): Exception? {
        var failure: Exception? = null
        withContext(NonCancellable) {
            try {
                rpc?.close()
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                failure = e
            }
        }
        try {
            client?.close()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            failure?.addSuppressed(e) ?: run { failure = e }
        }
        return failure
    }

    /** Ends the live session on purpose. [active] is cleared first, so it is cleared whatever the close does. */
    private suspend fun closeActive() {
        val previous = active ?: return
        active = null
        release(previous.client, previous.rpc)
    }

    private suspend fun fail(url: String, cause: GatewayException, opening: Opening) {
        release(opening.client, opening.rpc)?.let(cause::addSuppressed)
        _connectionState.value = GatewayConnection.Error(url, cause, warnings(gatewayKeyOf(url)))
    }

    /**
     * Ends the session. Runs to completion even when the caller is cancelled: a half-done disconnect could leave a
     * live session unwatched.
     */
    override suspend fun disconnect(): Unit = withContext(NonCancellable) {
        watcher?.cancelAndJoin()
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
     * @throws GatewayNotConnectedException if no session is active: retryable, with the URL, while the silent
     *   reconnect runs; not retryable otherwise.
     */
    fun requireSession(): GatewayRpcClient = active?.rpc ?: throw GatewayNotConnectedException(reconnectingUrl)

    /** @return `hello-ok.auth.scopes` of the live session, or `null` while disconnected. */
    fun grantedScopes(): Set<String>? = active?.hello?.scopes
}

/**
 * The live session of [GatewayConnectionManagerImpl].
 *
 * @param token The shared token the user connected with (also in [GatewayConnection.Connected.tokenOrNull]).
 * @param reconnected `true` for a session the silent reconnect opened, `false` for one the user opened.
 * @param liveSinceMs `elapsedRealtimeMs` when the session was opened.
 * @param warnings The [GatewayConnection.Connected.warnings] of this session.
 */
@Suppress("LongParameterList") // a plain holder of what one connect produced
private class ActiveSession(
    val url: String,
    val token: String?,
    val client: OpenClawApiClient,
    val rpc: GatewayRpcClient,
    val hello: HelloOk,
    val reconnected: Boolean,
    val liveSinceMs: Long,
    val warnings: List<ConnectionWarning>,
) {
    /**
     * @param nowMs `elapsedRealtimeMs` when the socket ended.
     * @return Whether losing this socket may trigger the silent reconnect: always for a session the user opened; for
     *   a reconnected one only after [RECONNECT_REARM_MS] live, so a gateway that keeps dropping the socket never
     *   loops while a blip hours later is still covered.
     */
    fun mayReconnect(nowMs: Long): Boolean = !reconnected || nowMs - liveSinceMs >= RECONNECT_REARM_MS
}

/** Capabilities come from the methods the gateway offers AND the scopes this socket holds (ADR-0007). */
private fun connectedState(session: ActiveSession, generation: Long) = GatewayConnection.Connected(
    url = session.url,
    displayName = session.hello.gatewayHost ?: DEFAULT_DISPLAY_NAME,
    version = session.hello.serverVersion,
    authRequirement = session.hello.toAuthRequirement(session.token),
    isHttps = session.url.startsWith("https://", ignoreCase = true),
    capabilities = gatewayCapabilities(session.hello.methods, session.hello.scopes),
    hardwareInfo = null,
    tokenOrNull = session.token,
    warnings = session.warnings,
    sessionGeneration = generation,
)

// ── Fail-safe default settings (no cleartext to public IPs) ──────────────────

private object AlwaysBlockCleartextSettingsRepository : SettingsRepository {
    override val allowCleartextPublicIPs = flowOf(false)
    override suspend fun setAllowCleartextPublicIPs(enabled: Boolean) = Unit
}

// ── Factory interface for testability ────────────────────────────────────────

interface OpenClawApiClientFactory {
    fun unauthenticated(baseUrl: String, allowCleartextPublicIPs: () -> Boolean = { false }): OpenClawApiClient
}

object DefaultClientFactory : OpenClawApiClientFactory {
    override fun unauthenticated(baseUrl: String, allowCleartextPublicIPs: () -> Boolean) =
        OpenClawApiClient.unauthenticated(baseUrl, allowCleartextPublicIPs)
}
