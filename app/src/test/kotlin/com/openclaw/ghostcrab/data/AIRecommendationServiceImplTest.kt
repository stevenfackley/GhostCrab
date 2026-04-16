package com.openclaw.ghostcrab.data

import com.openclaw.ghostcrab.data.api.OpenClawApiClient
import com.openclaw.ghostcrab.data.api.dto.AIRecommendationResponseDto
import com.openclaw.ghostcrab.data.api.dto.SuggestedChangeDto
import com.openclaw.ghostcrab.data.impl.AIRecommendationServiceImpl
import com.openclaw.ghostcrab.data.impl.GatewayConnectionManagerImpl
import com.openclaw.ghostcrab.domain.exception.AIQuotaExceededException
import com.openclaw.ghostcrab.domain.exception.AIServiceUnavailableException
import com.openclaw.ghostcrab.domain.exception.GatewayApiException
import com.openclaw.ghostcrab.domain.model.AuthRequirement
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import com.openclaw.ghostcrab.domain.model.OpenClawConfig
import com.openclaw.ghostcrab.domain.model.RecommendationContext
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AIRecommendationServiceImplTest {

    private lateinit var manager: GatewayConnectionManagerImpl
    private lateinit var client: OpenClawApiClient
    private lateinit var service: AIRecommendationServiceImpl

    private val url = "http://192.168.1.50:18789"

    private fun connected(vararg caps: String) = GatewayConnection.Connected(
        url = url,
        displayName = "GW",
        version = "1.0",
        authRequirement = AuthRequirement.None,
        isHttps = false,
        capabilities = caps.toList(),
    )

    private val context = RecommendationContext(
        activeConfig = OpenClawConfig(emptyMap(), null),
        hardwareInfo = null,
        activeModelId = null,
    )

    @BeforeEach
    fun setUp() {
        client = mockk(relaxed = true)
        manager = mockk {
            every { connectionState } returns MutableStateFlow(GatewayConnection.Disconnected)
            every { requireClient() } returns client
        }
        service = AIRecommendationServiceImpl(manager)
    }

    // ── isAvailable ───────────────────────────────────────────────────────────

    @Test fun `isAvailable true when connected with skill-ai-recommend capability`() = runTest {
        every { manager.connectionState } returns MutableStateFlow(connected("skill-ai-recommend"))
        assertTrue(service.isAvailable())
    }

    @Test fun `isAvailable false when connected without skill-ai-recommend capability`() = runTest {
        every { manager.connectionState } returns MutableStateFlow(connected("other-skill"))
        assertFalse(service.isAvailable())
    }

    @Test fun `isAvailable false when disconnected`() = runTest {
        every { manager.connectionState } returns MutableStateFlow(GatewayConnection.Disconnected)
        assertFalse(service.isAvailable())
    }

    @Test fun `isAvailable false when connecting`() = runTest {
        every { manager.connectionState } returns MutableStateFlow(GatewayConnection.Connecting(url))
        assertFalse(service.isAvailable())
    }

    // ── getRecommendation ─────────────────────────────────────────────────────

    @Test fun `getRecommendation maps response fields correctly`() = runTest {
        val dto = AIRecommendationResponseDto(
            recommendation = "Use HTTPS",
            suggestedChanges = listOf(
                SuggestedChangeDto("gateway", "http.port", "18789", "443", "HTTPS default")
            ),
        )
        coEvery { client.getAIRecommendation(any()) } returns dto

        val result = service.getRecommendation("how to secure?", context)

        assertEquals("how to secure?", result.query)
        assertEquals("Use HTTPS", result.recommendation)
        assertEquals(1, result.suggestedChanges.size)
        result.suggestedChanges[0].let { change ->
            assertEquals("gateway", change.section)
            assertEquals("http.port", change.key)
            assertEquals("18789", change.currentValue)
            assertEquals("443", change.suggestedValue)
            assertEquals("HTTPS default", change.rationale)
        }
    }

    @Test fun `getRecommendation maps empty suggestedChanges`() = runTest {
        coEvery { client.getAIRecommendation(any()) } returns
            AIRecommendationResponseDto(recommendation = "All good", suggestedChanges = emptyList())

        val result = service.getRecommendation("any?", context)
        assertTrue(result.suggestedChanges.isEmpty())
    }

    @Test fun `getRecommendation 404 throws AIServiceUnavailableException`() = runTest {
        coEvery { client.getAIRecommendation(any()) } throws GatewayApiException(url, 404)
        assertThrows<AIServiceUnavailableException> { service.getRecommendation("q", context) }
    }

    @Test fun `getRecommendation 429 throws AIQuotaExceededException`() = runTest {
        coEvery { client.getAIRecommendation(any()) } throws GatewayApiException(url, 429)
        assertThrows<AIQuotaExceededException> { service.getRecommendation("q", context) }
    }

    @Test fun `getRecommendation 500 re-throws GatewayApiException unchanged`() = runTest {
        coEvery { client.getAIRecommendation(any()) } throws GatewayApiException(url, 500)
        val ex = assertThrows<GatewayApiException> { service.getRecommendation("q", context) }
        assertEquals(500, ex.statusCode)
    }

    @Test fun `getRecommendation throws IllegalStateException when not connected`() = runTest {
        every { manager.requireClient() } throws IllegalStateException("No active gateway connection")
        assertThrows<IllegalStateException> { service.getRecommendation("q", context) }
    }
}
