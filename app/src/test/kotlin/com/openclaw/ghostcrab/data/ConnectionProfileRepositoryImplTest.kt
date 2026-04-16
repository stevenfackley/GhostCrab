package com.openclaw.ghostcrab.data

import com.openclaw.ghostcrab.data.impl.ConnectionProfileRepositoryImpl
import com.openclaw.ghostcrab.data.storage.ConnectionProfileStore
import com.openclaw.ghostcrab.data.storage.StoredProfile
import com.openclaw.ghostcrab.domain.model.ConnectionProfile
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ConnectionProfileRepositoryImplTest {

    private lateinit var store: ConnectionProfileStore
    private lateinit var repo: ConnectionProfileRepositoryImpl

    private fun storedProfile(id: String = "id1") = StoredProfile(
        id = id,
        displayName = "Local GW",
        url = "http://192.168.1.50:18789",
        lastConnectedAt = 1_700_000_000L,
        hasToken = true,
    )

    private fun domainProfile(id: String = "id1") = ConnectionProfile(
        id = id,
        displayName = "Local GW",
        url = "http://192.168.1.50:18789",
        lastConnectedAt = 1_700_000_000L,
        hasToken = true,
    )

    @BeforeEach
    fun setUp() {
        store = mockk(relaxed = true)
        repo = ConnectionProfileRepositoryImpl(store)
    }

    // ── getProfiles ───────────────────────────────────────────────────────────

    @Test fun `getProfiles maps StoredProfile to ConnectionProfile`() = runTest {
        every { store.getProfilesFlow() } returns flowOf(listOf(storedProfile()))

        val profiles = repo.getProfiles().first()

        assertEquals(1, profiles.size)
        profiles[0].let { p ->
            assertEquals("id1", p.id)
            assertEquals("Local GW", p.displayName)
            assertEquals("http://192.168.1.50:18789", p.url)
            assertEquals(1_700_000_000L, p.lastConnectedAt)
            assertTrue(p.hasToken)
        }
    }

    @Test fun `getProfiles returns empty list when store is empty`() = runTest {
        every { store.getProfilesFlow() } returns flowOf(emptyList())
        assertTrue(repo.getProfiles().first().isEmpty())
    }

    @Test fun `getProfiles maps multiple profiles preserving order`() = runTest {
        every { store.getProfilesFlow() } returns flowOf(
            listOf(storedProfile("a"), storedProfile("b"))
        )
        val profiles = repo.getProfiles().first()
        assertEquals(listOf("a", "b"), profiles.map { it.id })
    }

    // ── saveProfile ───────────────────────────────────────────────────────────

    @Test fun `saveProfile persists profile and token`() = runTest {
        repo.saveProfile(domainProfile(), token = "secret")
        coVerify { store.saveProfile(any()) }
        coVerify { store.saveToken("id1", "secret") }
    }

    @Test fun `saveProfile with null token saves null to store`() = runTest {
        repo.saveProfile(domainProfile(), token = null)
        coVerify { store.saveToken("id1", null) }
    }

    @Test fun `saveProfile passes correct StoredProfile fields to store`() = runTest {
        val captured = mutableListOf<StoredProfile>()
        coEvery { store.saveProfile(capture(captured)) } returns Unit

        repo.saveProfile(domainProfile(), token = null)

        assertEquals(1, captured.size)
        captured[0].let { sp ->
            assertEquals("id1", sp.id)
            assertEquals("Local GW", sp.displayName)
            assertEquals("http://192.168.1.50:18789", sp.url)
            assertEquals(1_700_000_000L, sp.lastConnectedAt)
            assertTrue(sp.hasToken)
        }
    }

    // ── deleteProfile ─────────────────────────────────────────────────────────

    @Test fun `deleteProfile delegates to store`() = runTest {
        repo.deleteProfile("id1")
        coVerify { store.deleteProfile("id1") }
    }

    // ── getToken ──────────────────────────────────────────────────────────────

    @Test fun `getToken delegates to store and returns value`() = runTest {
        coEvery { store.getToken("id1") } returns "my-token"
        assertEquals("my-token", repo.getToken("id1"))
    }

    @Test fun `getToken returns null when store has no token`() = runTest {
        coEvery { store.getToken("id1") } returns null
        assertNull(repo.getToken("id1"))
    }
}
