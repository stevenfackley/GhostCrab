package com.openclaw.ghostcrab.data

import com.openclaw.ghostcrab.data.api.OpenClawApiClient
import com.openclaw.ghostcrab.data.api.dto.ModelDto
import com.openclaw.ghostcrab.data.impl.GatewayConnectionManagerImpl
import com.openclaw.ghostcrab.data.impl.ModelRepositoryImpl
import com.openclaw.ghostcrab.domain.exception.GatewayApiException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ModelRepositoryImplTest {

    private lateinit var manager: GatewayConnectionManagerImpl
    private lateinit var client: OpenClawApiClient
    private lateinit var repo: ModelRepositoryImpl

    @BeforeEach
    fun setUp() {
        client = mockk(relaxed = true)
        manager = mockk {
            every { requireClient() } returns client
        }
        repo = ModelRepositoryImpl(manager)
    }

    // ── getModels ─────────────────────────────────────────────────────────────

    @Test fun `getModels maps DTOs to ModelInfo`() = runTest {
        coEvery { client.getModels() } returns listOf(
            ModelDto(id = "gpt-4o", provider = "openai", displayName = "GPT-4o", isActive = true,
                status = "ready", capabilities = listOf("vision")),
        )

        val result = repo.getModels()

        assertEquals(1, result.size)
        result[0].let { m ->
            assertEquals("gpt-4o", m.id)
            assertEquals("openai", m.provider)
            assertEquals("GPT-4o", m.displayName)
            assertTrue(m.isActive)
            assertEquals("ready", m.status)
            assertEquals(listOf("vision"), m.capabilities)
        }
    }

    @Test fun `getModels uses id as displayName when DTO displayName defaults`() = runTest {
        coEvery { client.getModels() } returns listOf(
            ModelDto(id = "llama3", provider = "ollama"),
        )
        assertEquals("llama3", repo.getModels()[0].displayName)
    }

    @Test fun `getModels isActive defaults to false`() = runTest {
        coEvery { client.getModels() } returns listOf(
            ModelDto(id = "llama3", provider = "ollama"),
        )
        assertFalse(repo.getModels()[0].isActive)
    }

    @Test fun `getModels returns empty list when gateway has no models`() = runTest {
        coEvery { client.getModels() } returns emptyList()
        assertTrue(repo.getModels().isEmpty())
    }

    @Test fun `getModels throws IllegalStateException when not connected`() = runTest {
        every { manager.requireClient() } throws IllegalStateException("No active gateway connection")
        assertThrows<IllegalStateException> { repo.getModels() }
    }

    @Test fun `getModels propagates GatewayApiException`() = runTest {
        coEvery { client.getModels() } throws GatewayApiException("http://x", 500)
        assertThrows<GatewayApiException> { repo.getModels() }
    }

    // ── setActiveModel ────────────────────────────────────────────────────────

    @Test fun `setActiveModel delegates to client`() = runTest {
        repo.setActiveModel("gpt-4o")
        coVerify { client.setActiveModel("gpt-4o") }
    }

    @Test fun `setActiveModel throws IllegalStateException when not connected`() = runTest {
        every { manager.requireClient() } throws IllegalStateException("No active gateway connection")
        assertThrows<IllegalStateException> { repo.setActiveModel("gpt-4o") }
    }

    @Test fun `setActiveModel propagates GatewayApiException`() = runTest {
        coEvery { client.setActiveModel(any()) } throws GatewayApiException("http://x", 404)
        assertThrows<GatewayApiException> { repo.setActiveModel("unknown") }
    }
}
