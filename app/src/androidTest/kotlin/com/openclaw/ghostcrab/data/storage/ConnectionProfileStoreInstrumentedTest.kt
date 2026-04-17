package com.openclaw.ghostcrab.data.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Exercises [ConnectionProfileStore] against the real Android Keystore +
 * EncryptedSharedPreferences stack. Requires a device/emulator running API 26+.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionProfileStoreInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: ConnectionProfileStore

    @Before
    fun setup() {
        // Purge any residual encrypted token file from previous runs.
        context.deleteSharedPreferences("ghostcrab_tokens")
        store = ConnectionProfileStore(context)
        // Clear any persisted profiles via the public API.
        runBlocking {
            store.getProfilesFlow().first().forEach { store.deleteProfile(it.id) }
        }
    }

    @After
    fun teardown() {
        context.deleteSharedPreferences("ghostcrab_tokens")
        runBlocking {
            store.getProfilesFlow().first().forEach { store.deleteProfile(it.id) }
        }
    }

    @Test
    fun saveToken_then_getToken_returns_same_value() = runBlocking {
        val id = UUID.randomUUID().toString()
        store.saveToken(id, "bearer-secret-xyz")
        assertEquals("bearer-secret-xyz", store.getToken(id))
    }

    @Test
    fun saveToken_null_clears_existing_token() = runBlocking {
        val id = UUID.randomUUID().toString()
        store.saveToken(id, "bearer-secret-xyz")
        store.saveToken(id, null)
        assertNull(store.getToken(id))
    }

    @Test
    fun tokens_are_isolated_per_profile() = runBlocking {
        val a = UUID.randomUUID().toString()
        val b = UUID.randomUUID().toString()
        store.saveToken(a, "token-a")
        store.saveToken(b, "token-b")
        assertEquals("token-a", store.getToken(a))
        assertEquals("token-b", store.getToken(b))
    }

    @Test
    fun getToken_returns_null_when_nothing_stored() = runBlocking {
        assertNull(store.getToken(UUID.randomUUID().toString()))
    }

    @Test
    fun saveProfile_then_getProfilesFlow_contains_it() = runBlocking {
        val profile = StoredProfile(
            id = UUID.randomUUID().toString(),
            displayName = "gw-1",
            url = "https://gw-1.local",
            lastConnectedAt = 1_700_000_000_000,
            hasToken = true,
        )
        store.saveProfile(profile)
        val profiles = store.getProfilesFlow().first()
        assertTrue(profiles.contains(profile))
    }

    @Test
    fun saveProfile_is_upsert_on_id() = runBlocking {
        val id = UUID.randomUUID().toString()
        store.saveProfile(StoredProfile(id, "old", "https://old", null, false))
        store.saveProfile(StoredProfile(id, "new", "https://new", 1L, true))
        val profiles = store.getProfilesFlow().first().filter { it.id == id }
        assertEquals(1, profiles.size)
        assertEquals("new", profiles[0].displayName)
    }

    @Test
    fun deleteProfile_removes_profile_and_clears_token() = runBlocking {
        val id = UUID.randomUUID().toString()
        store.saveProfile(StoredProfile(id, "gw", "https://gw", null, true))
        store.saveToken(id, "secret")
        store.deleteProfile(id)
        val remaining = store.getProfilesFlow().first().map { it.id }
        assertTrue(id !in remaining)
        assertNull(store.getToken(id))
    }

    @Test
    fun token_survives_store_recreation_with_same_context() = runBlocking {
        val id = UUID.randomUUID().toString()
        store.saveToken(id, "persisted-secret")
        val fresh = ConnectionProfileStore(context)
        assertEquals("persisted-secret", fresh.getToken(id))
    }
}
