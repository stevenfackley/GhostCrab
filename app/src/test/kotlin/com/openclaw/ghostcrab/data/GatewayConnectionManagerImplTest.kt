package com.openclaw.ghostcrab.data

import app.cash.turbine.test
import com.openclaw.ghostcrab.data.api.OpenClawApiClient
import com.openclaw.ghostcrab.data.api.dto.HealthResponse
import com.openclaw.ghostcrab.data.gateway.ClientInfo
import com.openclaw.ghostcrab.data.gateway.DeviceIdentity
import com.openclaw.ghostcrab.data.gateway.DeviceIdentityProvider
import com.openclaw.ghostcrab.data.gateway.DeviceTokenStore
import com.openclaw.ghostcrab.data.gateway.StoredDeviceToken
import com.openclaw.ghostcrab.data.impl.GatewayConnectionManagerImpl
import com.openclaw.ghostcrab.data.impl.OpenClawApiClientFactory
import com.openclaw.ghostcrab.data.ws.WsSession
import com.openclaw.ghostcrab.domain.exception.DeviceCredentialStorageException
import com.openclaw.ghostcrab.domain.exception.GatewayConnectAuthException
import com.openclaw.ghostcrab.domain.exception.GatewayNotConnectedException
import com.openclaw.ghostcrab.domain.exception.GatewayPairingRequiredException
import com.openclaw.ghostcrab.domain.exception.GatewayProtocolException
import com.openclaw.ghostcrab.domain.exception.GatewayTimeoutException
import com.openclaw.ghostcrab.domain.exception.GatewayUnreachableException
import com.openclaw.ghostcrab.domain.model.AuthRequirement
import com.openclaw.ghostcrab.domain.model.ConnectionWarning
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import com.openclaw.ghostcrab.domain.repository.SettingsRepository
import com.openclaw.ghostcrab.testutil.GatewayFixtures
import com.openclaw.ghostcrab.testutil.InMemoryDeviceTokenStore
import com.openclaw.ghostcrab.testutil.OPERATOR_SCOPES
import com.openclaw.ghostcrab.testutil.RFC8032_SEED
import com.openclaw.ghostcrab.testutil.ScriptedWsSession
import com.openclaw.ghostcrab.testutil.ScriptedWsSession.Companion.fixtures
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.GeneralSecurityException
import kotlin.coroutines.CoroutineContext

class GatewayConnectionManagerImplTest {

    private val url = "http://192.168.1.50:18789"
    private val fixedIdentity = DeviceIdentity.fromSeed(RFC8032_SEED)
    private val identityProvider = object : DeviceIdentityProvider {
        override suspend fun identity(): DeviceIdentity = fixedIdentity
    }
    private val clientInfo = ClientInfo(
        id = "openclaw-android",
        version = "0.3.0",
        platform = "android",
        displayName = "GhostCrab (Android)",
    )
    private val tokens = InMemoryDeviceTokenStore()
    private val blockCleartext = object : SettingsRepository {
        override val allowCleartextPublicIPs: Flow<Boolean> = flowOf(false)
        override suspend fun setAllowCleartextPublicIPs(enabled: Boolean) = Unit
    }
    private lateinit var rest: OpenClawApiClient
    private lateinit var factory: OpenClawApiClientFactory

    private fun restClient(session: WsSession?, wsUrl: String): OpenClawApiClient {
        val client = mockk<OpenClawApiClient>(relaxed = true)
        coEvery { client.health() } returns HealthResponse("live")
        if (session != null) coEvery { client.openWsSession() } returns session
        every { client.webSocketUrl } returns wsUrl
        return client
    }

    private fun TestScope.manager(
        session: WsSession?,
        settings: SettingsRepository = blockCleartext,
        ioDispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(testScheduler),
        identity: DeviceIdentityProvider = identityProvider,
        deviceTokens: DeviceTokenStore = tokens,
    ): GatewayConnectionManagerImpl {
        rest = restClient(session, "ws://192.168.1.50:18789/ws")
        factory = mockk { every { unauthenticated(any(), any()) } returns rest }
        return GatewayConnectionManagerImpl(
            identityProvider = identity,
            deviceTokens = deviceTokens,
            clientInfo = clientInfo,
            clientFactory = factory,
            settingsRepository = settings,
            ioDispatcher = ioDispatcher,
        )
    }

    /** `hello-ok-paired` from a gateway whose `snapshot.authMode` is `none`. */
    private fun openGatewayHello(): JsonObject = Json.parseToJsonElement(
        GatewayFixtures.text("hello-ok-paired").replace("\"authMode\":\"token\"", "\"authMode\":\"none\""),
    ).jsonObject

    private fun openGatewaySession() =
        ScriptedWsSession(respond = { method, _ -> if (method == "connect") openGatewayHello() else null })

    private fun storageFailure(gatewayKey: String) =
        DeviceCredentialStorageException("store the device token for $gatewayKey", GeneralSecurityException("ks"))

    // ── connect ───────────────────────────────────────────────────────────────

    @Test fun `shared-token connect signs the handshake and lands in Connected`() = runTest {
        val session = ScriptedWsSession(respond = fixtures("connect" to "hello-ok-paired"))
        val m = manager(session)
        m.connect(url, "shared-token")

        val state = m.connectionState.value as GatewayConnection.Connected
        assertEquals("ecb0a0611ff5", state.displayName)
        assertEquals("2026.10.3", state.version)
        assertEquals(AuthRequirement.Token, state.authRequirement)
        assertEquals(listOf("config", "models", "skills"), state.capabilities)
        assertEquals("shared-token", state.tokenOrNull)
        assertFalse(state.isHttps)
        assertTrue(state.warnings.isEmpty())
        val params = session.paramsOf("connect")
        assertEquals("shared-token", params.getValue("auth").jsonObject.getValue("token").jsonPrimitive.content)
        assertEquals(fixedIdentity.deviceId, params.getValue("device").jsonObject.getValue("id").jsonPrimitive.content)
    }

    @Test fun `connect with token transitions to Connected`() = runTest {
        val m = manager(ScriptedWsSession(respond = fixtures("connect" to "hello-ok-paired")))

        m.connectionState.test {
            assertEquals(GatewayConnection.Disconnected, awaitItem())
            m.connect(url, "shared-token")
            assertInstanceOf(GatewayConnection.Connecting::class.java, awaitItem())
            assertEquals(url, (awaitItem() as GatewayConnection.Connected).url)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `connect without token to a no-auth gateway transitions to Connected`() = runTest {
        val m = manager(openGatewaySession())

        m.connect(url, null)

        val state = m.connectionState.value as GatewayConnection.Connected
        assertEquals(AuthRequirement.None, state.authRequirement)
        assertNull(state.tokenOrNull)
    }

    @Test fun `the issued device token is stored with the granted scopes`() = runTest {
        manager(ScriptedWsSession(respond = fixtures("connect" to "hello-ok-paired"))).connect(url, "shared-token")
        val expected = StoredDeviceToken("dt-test-0001", OPERATOR_SCOPES, "operator", fixedIdentity.deviceId)
        assertEquals(expected, tokens.tokens[url])
    }

    @Test fun `a device token the store refuses is a warning, never a failed connect`() = runTest {
        val refusing = object : DeviceTokenStore {
            override suspend fun loadDeviceToken(gatewayKey: String): StoredDeviceToken? = null
            override suspend fun saveDeviceToken(gatewayKey: String, token: StoredDeviceToken) {
                throw storageFailure(gatewayKey)
            }
            override suspend fun clearDeviceToken(gatewayKey: String) = Unit
        }
        val m = manager(ScriptedWsSession(respond = fixtures("connect" to "hello-ok-paired")), deviceTokens = refusing)

        m.connect(url, "shared-token")

        val state = m.connectionState.value as GatewayConnection.Connected
        val warning = state.warnings.single() as ConnectionWarning.DeviceTokenNotSaved
        assertInstanceOf(DeviceCredentialStorageException::class.java, warning.cause)
        assertEquals(OPERATOR_SCOPES.toSet(), m.grantedScopes())
        assertFalse(state.toString().contains("dt-test-0001"), state.toString())
    }

    @Test fun `pairing required surfaces the request id and lands in Error`() = runTest {
        val session = ScriptedWsSession(
            closeAfterReplyTo = setOf("connect"),
            respond = fixtures("connect" to "err-pairing-required"),
        )
        val m = manager(session)
        val e = assertThrows<GatewayPairingRequiredException> { m.connect(url, "shared-token") }
        assertEquals("af0b5d81-7bd2-4b86-80b0-4a489b541974", e.requestId)
        val state = m.connectionState.value as GatewayConnection.Error
        assertSame(e, state.cause)
        assertTrue(state.warnings.isEmpty())
        assertTrue(session.closedByClient)
    }

    @Test fun `a regenerated identity explains the new pairing request`() = runTest {
        val regenerated = object : DeviceIdentityProvider {
            override suspend fun identity(): DeviceIdentity = fixedIdentity
            override val regenerated: StateFlow<Boolean> = MutableStateFlow(true)
        }
        val session = ScriptedWsSession(
            closeAfterReplyTo = setOf("connect"),
            respond = fixtures("connect" to "err-pairing-required"),
        )
        val m = manager(session, identity = regenerated)

        assertThrows<GatewayPairingRequiredException> { m.connect(url, "shared-token") }

        val state = m.connectionState.value as GatewayConnection.Error
        assertEquals(listOf(ConnectionWarning.DeviceIdentityRegenerated), state.warnings)
    }

    @Test fun `a wrong token is a typed connect-auth error`() = runTest {
        val session = ScriptedWsSession(
            closeAfterReplyTo = setOf("connect"),
            respond = fixtures("connect" to "err-token-mismatch"),
        )
        val m = manager(session)
        val e = assertThrows<GatewayConnectAuthException> { m.connect(url, "wrong") }
        assertEquals("AUTH_TOKEN_MISMATCH", e.detailCode)
        assertSame(e, (m.connectionState.value as GatewayConnection.Error).cause)
    }

    @Test fun `an unreachable gateway never opens the socket`() = runTest {
        val m = manager(session = null)
        coEvery { rest.health() } throws GatewayUnreachableException(url)
        assertThrows<GatewayUnreachableException> { m.connect(url, null) }
        coVerify(exactly = 0) { rest.openWsSession() }
        val state = m.connectionState.value as GatewayConnection.Error
        assertEquals(url, state.url)
        assertInstanceOf(GatewayUnreachableException::class.java, state.cause)
    }

    @Test fun `connect emits Error and rethrows on timeout`() = runTest {
        val m = manager(session = null)
        coEvery { rest.health() } throws GatewayTimeoutException(url)

        val exception = runCatching { m.connect(url, null) }.exceptionOrNull()

        assertInstanceOf(GatewayTimeoutException::class.java, exception)
        assertInstanceOf(GatewayConnection.Error::class.java, m.connectionState.value)
    }

    @Test fun `without a shared token the stored device token is reused with its scopes`() = runTest {
        tokens.tokens[url] = StoredDeviceToken("dt-old", listOf("operator.read"), "operator", fixedIdentity.deviceId)
        val session = ScriptedWsSession(respond = fixtures("connect" to "hello-ok-paired"))
        manager(session).connect(url, null)
        val params = session.paramsOf("connect")
        assertEquals("dt-old", params.getValue("auth").jsonObject.getValue("deviceToken").jsonPrimitive.content)
        assertEquals(listOf("operator.read"), params.getValue("scopes").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun `a device token stored for another identity is ignored`() = runTest {
        tokens.tokens[url] = StoredDeviceToken("dt-other", listOf("operator.read"), "operator", "another-device")
        val session = ScriptedWsSession(
            closeAfterReplyTo = setOf("connect"),
            respond = fixtures("connect" to "err-token-missing"),
        )
        val e = assertThrows<GatewayConnectAuthException> { manager(session).connect(url, null) }
        assertEquals("AUTH_TOKEN_MISSING", e.detailCode)
        assertNull(session.paramsOf("connect")["auth"])
    }

    @Test fun `a rejected device token is forgotten`() = runTest {
        tokens.tokens[url] = StoredDeviceToken("dt-revoked", OPERATOR_SCOPES, "operator", fixedIdentity.deviceId)
        val m = manager(ScriptedWsSession(respond = { _, _ -> deviceTokenRejection() }))
        assertThrows<GatewayConnectAuthException> { m.connect(url, null) }
        assertNull(tokens.tokens[url])
    }

    @Test fun `a rejected device token the store cannot forget still lands in Error with the rejection`() = runTest {
        val stuck = object : DeviceTokenStore {
            override suspend fun loadDeviceToken(gatewayKey: String) =
                StoredDeviceToken("dt-revoked", OPERATOR_SCOPES, "operator", fixedIdentity.deviceId)
            override suspend fun saveDeviceToken(gatewayKey: String, token: StoredDeviceToken) = Unit
            override suspend fun clearDeviceToken(gatewayKey: String) {
                throw storageFailure(gatewayKey)
            }
        }
        val m = manager(ScriptedWsSession(respond = { _, _ -> deviceTokenRejection() }), deviceTokens = stuck)

        val e = assertThrows<GatewayConnectAuthException> { m.connect(url, null) }

        assertInstanceOf(DeviceCredentialStorageException::class.java, e.suppressed.single())
        assertSame(e, (m.connectionState.value as GatewayConnection.Error).cause)
    }

    @Test fun `a cancelled handshake ends Disconnected and closes the socket`() = runTest {
        val session = ScriptedWsSession(respond = { _, _ -> null })
        val m = manager(session)
        val job = launch { m.connect(url, "shared-token") }
        runCurrent()
        assertInstanceOf(GatewayConnection.Connecting::class.java, m.connectionState.value)
        job.cancel()
        job.join()
        assertEquals(GatewayConnection.Disconnected, m.connectionState.value)
        assertTrue(session.closedByClient)
    }

    @Test fun `an outer timeout propagates as cancellation, never as a gateway error`() = runTest {
        val session = ScriptedWsSession(respond = { _, _ -> null })
        val m = manager(session)

        assertThrows<TimeoutCancellationException> { withTimeout(1_000) { m.connect(url, "shared-token") } }

        assertEquals(GatewayConnection.Disconnected, m.connectionState.value)
        assertTrue(session.closedByClient)
    }

    // ── probeAuth ─────────────────────────────────────────────────────────────

    @Test fun `probeAuth maps a missing token to Token and sends no credential`() = runTest {
        val session = ScriptedWsSession(respond = fixtures("connect" to "err-token-missing"))
        assertEquals(AuthRequirement.Token, manager(session).probeAuth(url))
        assertNull(session.paramsOf("connect")["auth"])
        assertTrue(session.closedByClient)
    }

    @Test fun `probeAuth maps a pending approval to None`() = runTest {
        val m = manager(ScriptedWsSession(respond = fixtures("connect" to "err-pairing-required")))
        assertEquals(AuthRequirement.None, m.probeAuth(url))
    }

    @Test fun `probeAuth reads the auth mode from hello-ok`() = runTest {
        val m = manager(ScriptedWsSession(respond = fixtures("connect" to "hello-ok-paired")))
        assertEquals(AuthRequirement.Token, m.probeAuth(url))
        assertTrue(tokens.tokens.isEmpty(), "the probe stores nothing")
    }

    @Test fun `probeAuth returns None when the gateway auth mode is none`() = runTest {
        val m = manager(openGatewaySession())
        assertEquals(AuthRequirement.None, m.probeAuth(url))
    }

    // ── session ───────────────────────────────────────────────────────────────

    @Test fun `check sends health over the live socket`() = runTest {
        val session = ScriptedWsSession(respond = fixtures("connect" to "hello-ok-paired", "health" to "res-health"))
        val m = manager(session)
        m.connect(url, "shared-token")
        m.check()
        assertEquals(listOf("connect", "health"), session.sent.map { it.getValue("method").jsonPrimitive.content })
    }

    @Test fun `granted scopes come from hello-ok and disconnect drops them`() = runTest {
        val session = ScriptedWsSession(respond = fixtures("connect" to "hello-ok-paired"))
        val m = manager(session)
        m.connect(url, "shared-token")
        assertEquals(OPERATOR_SCOPES.toSet(), m.grantedScopes())
        assertNotNull(m.requireSession())
        m.disconnect()
        assertNull(m.grantedScopes())
        assertTrue(session.closedByClient)
        verify { rest.close() }
        assertEquals(GatewayConnection.Disconnected, m.connectionState.value)
        assertThrows<GatewayNotConnectedException> { m.check() }
    }

    @Test fun `requireClient and requireSession before connect throw GatewayNotConnectedException`() = runTest {
        val m = manager(session = null)
        assertThrows<GatewayNotConnectedException> { m.requireClient() }
        assertThrows<GatewayNotConnectedException> { m.requireSession() }
    }

    // ── Kept from main, adapted to the WebSocket flow ────────────────────────

    @Test fun `two concurrent connect calls result in exactly one Connected state`() = runTest {
        val url2 = "http://192.168.1.51:18789"
        val first = ScriptedWsSession(respond = fixtures("connect" to "hello-ok-paired"))
        val second = ScriptedWsSession(respond = fixtures("connect" to "hello-ok-paired"))
        val m = manager(first)
        every { factory.unauthenticated(url2, any()) } returns restClient(second, "ws://192.168.1.51:18789/ws")

        val job1 = async { runCatching { m.connect(url, "shared-token") } }
        val job2 = async { runCatching { m.connect(url2, "shared-token") } }
        awaitAll(job1, job2)

        // The mutex serialises them: whichever ran first had its socket closed by the second.
        val finalUrl = (m.connectionState.value as GatewayConnection.Connected).url
        assertTrue(if (finalUrl == url) second.closedByClient else first.closedByClient)
    }

    @Test fun `connect wraps an unclassified exception and lands in Error, never stuck Connecting`() = runTest {
        val m = manager(session = null)
        coEvery { rest.openWsSession() } throws IllegalStateException("engine rejected URL")

        val exception = runCatching { m.connect(url, null) }.exceptionOrNull()

        assertInstanceOf(GatewayProtocolException::class.java, exception)
        assertInstanceOf(IllegalStateException::class.java, exception!!.cause)
        assertEquals(url, (m.connectionState.value as GatewayConnection.Error).url)
        verify { rest.close() }
    }

    @Test fun `Connected toString never contains the token`() = runTest {
        val secret = "super-secret-bearer-value"
        val m = manager(ScriptedWsSession(respond = fixtures("connect" to "hello-ok-paired")))

        m.connect(url, secret)

        val rendered = m.connectionState.value.toString()
        assertTrue(rendered.startsWith("Connected("), rendered)
        assertFalse(rendered.contains(secret), rendered)
        assertTrue(rendered.contains("tokenOrNull=[REDACTED]"), rendered)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `connect runs on the injected IO dispatcher`() = runTest {
        val io = CountingDispatcher(StandardTestDispatcher(testScheduler))
        val m = manager(ScriptedWsSession(respond = fixtures("connect" to "hello-ok-paired")), ioDispatcher = io)
        val before = io.dispatches

        m.connect(url, "shared-token")

        assertTrue(io.dispatches > before)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `turning the cleartext opt-in off applies to the live session`() = runTest {
        val setting = MutableStateFlow(true)
        val settings = object : SettingsRepository {
            override val allowCleartextPublicIPs: Flow<Boolean> = setting
            override suspend fun setAllowCleartextPublicIPs(enabled: Boolean) {
                setting.value = enabled
            }
        }
        val m = manager(ScriptedWsSession(respond = fixtures("connect" to "hello-ok-paired")), settings)
        val policy = slot<() -> Boolean>()
        every { factory.unauthenticated(any(), capture(policy)) } returns rest

        m.connect(url, "shared-token")
        assertTrue(policy.captured())

        setting.value = false
        assertFalse(policy.captured())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `a settings read error fails closed and does not propagate`() = runTest {
        val source = MutableSharedFlow<Boolean>(replay = 1)
        val settings = object : SettingsRepository {
            override val allowCleartextPublicIPs: Flow<Boolean> =
                source.map { if (it) true else throw java.io.IOException("datastore gone") }
            override suspend fun setAllowCleartextPublicIPs(enabled: Boolean) = Unit
        }
        source.tryEmit(true)
        val m = manager(ScriptedWsSession(respond = fixtures("connect" to "hello-ok-paired")), settings)
        val policy = slot<() -> Boolean>()
        every { factory.unauthenticated(any(), capture(policy)) } returns rest

        m.connect(url, "shared-token")
        assertTrue(policy.captured())

        source.tryEmit(false)
        assertFalse(policy.captured())
    }
}

/** `connect` refused because the stored device token was revoked (`AUTH_DEVICE_TOKEN_MISMATCH`). */
private fun deviceTokenRejection(): JsonObject = buildJsonObject {
    put("type", "res")
    put("ok", false)
    putJsonObject("error") {
        put("code", "INVALID_REQUEST")
        put("message", "unauthorized: device token mismatch")
        putJsonObject("details") { put("code", "AUTH_DEVICE_TOKEN_MISMATCH") }
    }
}

private class CountingDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
    var dispatches = 0
        private set

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatches++
        delegate.dispatch(context, block)
    }
}
