package com.openclaw.ghostcrab.data

import com.openclaw.ghostcrab.data.impl.AIRecommendationServiceImpl
import com.openclaw.ghostcrab.data.impl.GatewayConnectionManagerImpl
import com.openclaw.ghostcrab.domain.exception.AIServiceUnavailableException
import com.openclaw.ghostcrab.domain.exception.GatewayNotConnectedException
import com.openclaw.ghostcrab.domain.model.AuthRequirement
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import com.openclaw.ghostcrab.domain.model.OpenClawConfig
import com.openclaw.ghostcrab.domain.model.RecommendationContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * OpenClaw has no recommendation API (QA §8.11, ADR-0007): the service is honestly unsupported. The REST-era
 * cases (DTO mapping, 404/429/500 mapping) are gone with `POST /api/ai/recommend`; their still-valid intent (never
 * available while disconnected or connecting, typed not-connected error, no secret ever leaves the device) is kept.
 */
class AIRecommendationServiceImplTest {

    private val url = "http://192.168.1.50:18789"
    private val context =
        RecommendationContext(OpenClawConfig(emptyMap(), null), hardwareInfo = null, activeModelId = null)

    private fun manager(state: GatewayConnection): GatewayConnectionManagerImpl = mockk {
        every { connectionState } returns MutableStateFlow(state)
    }

    private fun connected(vararg capabilities: String) = GatewayConnection.Connected(
        url = url,
        displayName = "GW",
        version = "2026.10.3",
        authRequirement = AuthRequirement.Token,
        isHttps = false,
        capabilities = capabilities.toList(),
    )

    // ── isAvailable ───────────────────────────────────────────────────────────

    @Test fun `never available, whatever the gateway advertises`() = runTest {
        val everything = manager(connected("ai.recommend", "config", "models", "skills"))
        assertFalse(AIRecommendationServiceImpl(everything).isAvailable())
    }

    @Test fun `isAvailable false when connected without ai_recommend capability`() = runTest {
        assertFalse(AIRecommendationServiceImpl(manager(connected("config"))).isAvailable())
    }

    @Test fun `isAvailable false when disconnected`() = runTest {
        assertFalse(AIRecommendationServiceImpl(manager(GatewayConnection.Disconnected)).isAvailable())
    }

    @Test fun `isAvailable false when connecting`() = runTest {
        assertFalse(AIRecommendationServiceImpl(manager(GatewayConnection.Connecting(url))).isAvailable())
    }

    // ── getRecommendation ─────────────────────────────────────────────────────

    @Test fun `getRecommendation reports the feature unavailable without touching the session`() = runTest {
        val m = manager(connected("ai.recommend"))
        val service = AIRecommendationServiceImpl(m)
        val e = assertThrows<AIServiceUnavailableException> { service.getRecommendation("q", context) }
        assertEquals(url, e.url)
        assertFalse(e.isRetryable)
        verify(exactly = 0) { m.requireSession() }
        verify(exactly = 0) { m.requireClient() }
    }

    @Test fun `getRecommendation while disconnected is GatewayNotConnectedException`() = runTest {
        assertThrows<GatewayNotConnectedException> {
            AIRecommendationServiceImpl(manager(GatewayConnection.Disconnected)).getRecommendation("q", context)
        }
    }

    @Test fun `a config holding secrets never leaves the device`() = runTest {
        val m = manager(connected("ai.recommend"))
        val secretContext = RecommendationContext(
            activeConfig = OpenClawConfig(
                mapOf("gateway" to Json.parseToJsonElement("""{"auth":{"mode":"token","token":"s3cr3t"}}""")),
            ),
            hardwareInfo = null,
            activeModelId = null,
        )
        val e = assertThrows<AIServiceUnavailableException> {
            AIRecommendationServiceImpl(m).getRecommendation("q", secretContext)
        }
        assertFalse(e.message.orEmpty().contains("s3cr3t"))
        verify(exactly = 0) { m.requireSession() }
        verify(exactly = 0) { m.requireClient() }
    }
}
