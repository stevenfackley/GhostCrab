package com.openclaw.ghostcrab.data

import com.openclaw.ghostcrab.data.api.OpenClawApiClient
import com.openclaw.ghostcrab.data.impl.ConfigRepositoryImpl
import com.openclaw.ghostcrab.data.impl.GatewayConnectionManagerImpl
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ConfigRepositoryImplTest {

    private lateinit var mockManager: GatewayConnectionManagerImpl
    private lateinit var mockClient: OpenClawApiClient
    private lateinit var repo: ConfigRepositoryImpl

    private val sectionMap: Map<String, JsonElement> = mapOf(
        "gateway" to JsonPrimitive("value"),
    )

    @BeforeEach
    fun setUp() {
        mockClient = mockk(relaxed = true)
        mockManager = mockk {
            every { requireClient() } returns mockClient
        }
        repo = ConfigRepositoryImpl(mockManager)
    }

    @Test
    fun `getConfig delegates to client and returns OpenClawConfig`() = runTest {
        coEvery { mockClient.getConfig() } returns Pair(sectionMap, "etag-abc")

        val config = repo.getConfig()

        assertEquals(sectionMap, config.sections)
        assertEquals("etag-abc", config.etag)
    }

    @Test
    fun `getConfig caches etag for subsequent updateConfig call`() = runTest {
        coEvery { mockClient.getConfig() } returns Pair(sectionMap, "etag-v1")
        repo.getConfig() // caches etag-v1

        val patchValue = JsonPrimitive("new-value")
        repo.updateConfig("gateway", patchValue)

        coVerify { mockClient.updateConfig("gateway", patchValue, "etag-v1") }
    }

    @Test
    fun `updateConfig passes null etag before any getConfig call`() = runTest {
        val patchValue = JsonPrimitive("x")
        repo.updateConfig("gateway", patchValue)

        coVerify { mockClient.updateConfig("gateway", patchValue, null) }
    }

    @Test
    fun `getConfig when not connected throws IllegalStateException`() = runTest {
        every { mockManager.requireClient() } throws IllegalStateException("No active gateway connection")

        assertThrows<IllegalStateException> {
            repo.getConfig()
        }
    }
}
