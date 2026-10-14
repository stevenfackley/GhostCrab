package com.openclaw.ghostcrab.data

import android.content.Context
import android.content.SharedPreferences
import com.openclaw.ghostcrab.data.storage.ConnectionProfileStore
import com.openclaw.ghostcrab.data.storage.StoredProfile
import com.openclaw.ghostcrab.data.storage.decodeProfilesForRead
import com.openclaw.ghostcrab.data.storage.decodeProfilesForWrite
import com.openclaw.ghostcrab.domain.exception.ProfileNeedsReauthException
import com.openclaw.ghostcrab.domain.exception.ProfileStoreCorruptedException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.security.GeneralSecurityException

/**
 * Tests for [ConnectionProfileStore] exercising the `getToken` path via an injected
 * [SharedPreferences] factory. The production default (Keystore-backed
 * EncryptedSharedPreferences) cannot be used in JVM unit tests, so we inject a mock.
 */
class ConnectionProfileStoreTest {

    private fun makeStore(prefs: SharedPreferences): ConnectionProfileStore {
        val context = mockk<Context>(relaxed = true)
        return ConnectionProfileStore(context) { prefs }
    }

    @Test
    fun `getToken returns null when no token stored`() = runTest {
        val prefs = mockk<SharedPreferences> {
            every { getString(any(), null) } returns null
        }
        val store = makeStore(prefs)

        val result = store.getToken("profile-1")

        assertNull(result)
    }

    @Test
    fun `getToken returns stored token`() = runTest {
        val prefs = mockk<SharedPreferences> {
            every { getString("token_profile-1", null) } returns "my-token"
        }
        val store = makeStore(prefs)

        val result = store.getToken("profile-1")

        assertEquals("my-token", result)
    }

    @Test
    fun `getToken throws ProfileNeedsReauthException when decryption fails`() {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        val prefs = mockk<SharedPreferences> {
            every { getString(any(), null) } throws SecurityException("Keystore unavailable")
            every { edit() } returns editor
        }
        val store = makeStore(prefs)

        assertThrows(ProfileNeedsReauthException::class.java) {
            runBlocking { store.getToken("profile-1") }
        }
    }

    @Test
    fun `getToken clears corrupted token before throwing`() {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        val prefs = mockk<SharedPreferences> {
            every { getString(any(), null) } throws SecurityException("Keystore unavailable")
            every { edit() } returns editor
        }
        val store = makeStore(prefs)

        runCatching { runBlocking { store.getToken("profile-1") } }

        verify { editor.remove("token_profile-1") }
    }

    @Test
    fun `decodeProfilesForWrite refuses to overwrite undecodable data`() {
        assertThrows(ProfileStoreCorruptedException::class.java) { decodeProfilesForWrite("""[{"id":1}]""") }
    }

    @Test
    fun `decodeProfilesForRead tolerates unknown fields from a newer schema`() {
        val json = """[{"id":"a","displayName":"A","url":"http://10.0.0.2:18789",""" +
            """"lastConnectedAt":null,"hasToken":false,"pinned":true}]"""
        assertEquals(listOf("a"), decodeProfilesForRead(json).map { it.id })
    }

    @Test
    fun `decodeProfilesForRead shows nothing for corrupt data instead of throwing`() {
        assertEquals(emptyList<StoredProfile>(), decodeProfilesForRead("not json"))
    }

    @Test
    fun `saveToken maps a keystore failure to ProfileNeedsReauthException`() {
        val store = ConnectionProfileStore(mockk(relaxed = true)) { throw GeneralSecurityException("keystore gone") }
        assertThrows(ProfileNeedsReauthException::class.java) { runBlocking { store.saveToken("p1", "t") } }
    }
}
