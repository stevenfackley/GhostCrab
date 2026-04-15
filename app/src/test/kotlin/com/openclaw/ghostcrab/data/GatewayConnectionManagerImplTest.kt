package com.openclaw.ghostcrab.data

import app.cash.turbine.test
import com.openclaw.ghostcrab.data.api.OpenClawApiClient
import com.openclaw.ghostcrab.data.api.dto.HealthResponse
import com.openclaw.ghostcrab.data.api.dto.StatusResponse
import com.openclaw.ghostcrab.data.impl.GatewayConnectionManagerImpl
import com.openclaw.ghostcrab.data.impl.OpenClawApiClientFactory
import com.openclaw.ghostcrab.domain.exception.GatewayAuthException
import com.openclaw.ghostcrab.domain.exception.GatewayTimeoutException
import com.openclaw.ghostcrab.domain.exception.GatewayUnreachableException
import com.openclaw.ghostcrab.domain.model.AuthRequirement
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GatewayConnectionManagerImplTest {

    private lateinit var probeClient: OpenClawApiClient
    private lateinit var sessionClient: OpenClawApiClient
    private lateinit var factory: OpenClawApiClientFactory
    private lateinit var manager: GatewayConnectionManagerImpl

    private val url = "http://192.168.1.50:18789"
    private val token = "test-token"
    private val statusOk = StatusResponse(
        displayName = "My Gateway",
        version = "1.0.0",
        capabilities = listOf("skill-ai-recommend"),
        hardware = null,
    )

    @BeforeEach
    fun setUp() {
        probeClient = mockk(relaxed = true)
        sessionClient = mockk(relaxed = true)
        factory = mockk {
            every { unauthenticated(any()) } returns probeClient
            every { authenticated(any(), any()) } returns sessionClient
        }
        manager = GatewayConnectionManagerImpl(factory)
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    fun `connect with token transitions to Connected`() = runTest {
        coEvery { probeClient.health() } returns HealthResponse("ok")
        coEvery { probeClient.status() } throws GatewayAuthException(url, 401)
        coEvery { sessionClient.status() } returns statusOk

        manager.connectionState.test {
            assertEquals(GatewayConnection.Disconnected, awaitItem())
            manager.connect(url, token)
            val connecting = awaitItem()
            assertInstanceOf(GatewayConnection.Connecting::class.java, connecting)
            val connected = awaitItem() as GatewayConnection.Connected
            assertEquals(url, connected.url)
            assertEquals("My Gateway", connected.displayName)
            assertEquals("1.0.0", connected.version)
            assertEquals(AuthRequirement.Token, connected.authRequirement)
            assertTrue(!connected.isHttps)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `connect without token and no-auth gateway transitions to Connected`() = runTest {
        coEvery { probeClient.health() } returns HealthResponse("ok")
        coEvery { probeClient.status() } returns statusOk

        manager.connect(url, null)

        val state = manager.connectionState.value as GatewayConnection.Connected
        assertEquals(AuthRequirement.None, state.authRequirement)
    }

    @Test
    fun `disconnect transitions to Disconnected`() = runTest {
        coEvery { probeClient.health() } returns HealthResponse("ok")
        coEvery { probeClient.status() } returns statusOk
        manager.connect(url, null)

        manager.disconnect()

        assertEquals(GatewayConnection.Disconnected, manager.connectionState.value)
        verify { sessionClient.close() }
    }

    // ── Error paths ───────────────────────────────────────────────────────────

    @Test
    fun `connect emits Error and rethrows when host unreachable`() = runTest {
        coEvery { probeClient.health() } throws GatewayUnreachableException(url)

        val exception = runCatching { manager.connect(url, null) }.exceptionOrNull()

        assertInstanceOf(GatewayUnreachableException::class.java, exception)
        val state = manager.connectionState.value as GatewayConnection.Error
        assertEquals(url, state.url)
        assertInstanceOf(GatewayUnreachableException::class.java, state.cause)
    }

    @Test
    fun `connect emits Error and rethrows on auth failure`() = runTest {
        coEvery { probeClient.health() } returns HealthResponse("ok")
        coEvery { probeClient.status() } throws GatewayAuthException(url, 401)
        coEvery { sessionClient.status() } throws GatewayAuthException(url, 401)

        val exception = runCatching { manager.connect(url, "wrong-token") }.exceptionOrNull()

        assertInstanceOf(GatewayAuthException::class.java, exception)
        assertInstanceOf(GatewayConnection.Error::class.java, manager.connectionState.value)
    }

    @Test
    fun `connect emits Error and rethrows on timeout`() = runTest {
        coEvery { probeClient.health() } throws GatewayTimeoutException(url)

        val exception = runCatching { manager.connect(url, null) }.exceptionOrNull()

        assertInstanceOf(GatewayTimeoutException::class.java, exception)
        assertInstanceOf(GatewayConnection.Error::class.java, manager.connectionState.value)
    }

    @Test
    fun `probeAuth returns None when status is 200 without token`() = runTest {
        coEvery { probeClient.health() } returns HealthResponse("ok")
        coEvery { probeClient.status() } returns statusOk

        val result = manager.probeAuth(url)

        assertEquals(AuthRequirement.None, result)
    }

    @Test
    fun `probeAuth returns Token when status returns 401`() = runTest {
        coEvery { probeClient.health() } returns HealthResponse("ok")
        coEvery { probeClient.status() } throws GatewayAuthException(url, 401)

        val result = manager.probeAuth(url)

        assertEquals(AuthRequirement.Token, result)
    }
}
