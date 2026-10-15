package com.openclaw.ghostcrab.data.gateway

import android.content.Context
import android.content.SharedPreferences
import com.openclaw.ghostcrab.data.storage.ConnectionProfileStore
import com.openclaw.ghostcrab.data.storage.StoredProfile
import com.openclaw.ghostcrab.data.storage.orphanedGatewayKey
import com.openclaw.ghostcrab.domain.exception.DeviceCredentialStorageException
import com.openclaw.ghostcrab.testutil.InMemorySharedPreferences
import com.openclaw.ghostcrab.testutil.OPERATOR_SCOPES
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.GeneralSecurityException

class DeviceTokenStoreTest {

    private val prefs = InMemorySharedPreferences()
    private val token = StoredDeviceToken("dt-test-0001", OPERATOR_SCOPES, "operator", "device-1")

    private fun TestScope.store() =
        ConnectionProfileStore(mockk<Context>(relaxed = true), StandardTestDispatcher(testScheduler)) { prefs }

    private fun TestScope.storeOver(factory: (Context) -> SharedPreferences) =
        ConnectionProfileStore(mockk<Context>(relaxed = true), StandardTestDispatcher(testScheduler), factory)

    @Test fun `gateway keys are the normalised base URL`() {
        assertEquals("http://192.168.1.50:18789", gatewayKeyOf("http://192.168.1.50:18789/"))
        assertEquals("http://gw.local:18789", gatewayKeyOf("http://gw.local"))
        assertEquals("https://gw.example:443", gatewayKeyOf("https://gw.example/ws"))
        assertEquals("garbage", gatewayKeyOf(" garbage/ "))
    }

    @Test fun `gateway keys ignore host case`() {
        assertEquals(gatewayKeyOf("http://gw.local:18789"), gatewayKeyOf("http://GW.local:18789"))
        assertEquals("https://gw.example:443", gatewayKeyOf("HTTPS://GW.Example/ws"))
    }

    @Test fun `save then load round-trips`() = runTest {
        val store = store()
        store.saveDeviceToken("http://gw.local:18789", token)
        assertEquals(token, store.loadDeviceToken("http://gw.local:18789"))
        assertNull(store.loadDeviceToken("http://other:18789"))
    }

    @Test fun `clear removes only that gateway`() = runTest {
        val store = store()
        store.saveDeviceToken("a", token)
        store.saveDeviceToken("b", token)
        store.clearDeviceToken("a")
        assertNull(store.loadDeviceToken("a"))
        assertEquals(token, store.loadDeviceToken("b"))
    }

    @Test fun `an undecodable entry reads as null and is removed`() = runTest {
        prefs.edit().putString("device_token_a", "{not json").commit()
        assertNull(store().loadDeviceToken("a"))
        assertFalse(prefs.contains("device_token_a"))
    }

    @Test fun `wiping the token file wipes device tokens too`() = runTest {
        val store = store()
        store.saveDeviceToken("a", token)
        store.wipeTokens()
        assertNull(store.loadDeviceToken("a"))
    }

    @Test fun `device and profile tokens share one encrypted prefs instance`() = runTest {
        // EncryptedSharedPreferences must not be opened twice on the same file.
        var opened = 0
        val store = ConnectionProfileStore(mockk<Context>(relaxed = true), StandardTestDispatcher(testScheduler)) {
            opened++
            prefs
        }
        store.saveToken("profile-1", "shared-token")
        store.saveDeviceToken("a", token)
        assertEquals(token, store.loadDeviceToken("a"))
        assertEquals("shared-token", store.getToken("profile-1"))
        assertEquals(1, opened)
    }

    @Test fun `a save the Keystore refuses is a typed error naming the gateway, never the token`() = runTest {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true) {
            every { putString(any(), any()) } throws SecurityException("Could not encrypt data")
        }
        val prefs = mockk<SharedPreferences> { every { edit() } returns editor }
        val error = assertThrows<DeviceCredentialStorageException> {
            storeOver { prefs }.saveDeviceToken("http://gw.local:18789", token)
        }
        assertTrue("http://gw.local:18789" in error.message.orEmpty())
        assertFalse("dt-test-0001" in error.message.orEmpty())
        assertTrue(error.cause is SecurityException)
    }

    @Test fun `a token file that cannot be opened fails save and clear with typed errors`() = runTest {
        val store = storeOver { throw GeneralSecurityException("keyset unusable") }
        assertThrows<DeviceCredentialStorageException> { store.saveDeviceToken("a", token) }
        assertThrows<DeviceCredentialStorageException> { store.clearDeviceToken("a") }
        assertNull(store.loadDeviceToken("a")) // a load reads as "no token": the next connect uses the shared token
    }

    @Test fun `an entry the Keystore cannot decrypt reads as null and is removed`() = runTest {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true) { every { remove(any()) } returns this }
        val prefs = mockk<SharedPreferences> {
            every { getString("device_token_a", null) } throws SecurityException("Could not decrypt value")
            every { edit() } returns editor
        }
        assertNull(storeOver { prefs }.loadDeviceToken("a"))
        verify { editor.remove("device_token_a") }
    }

    @Test fun `deleting the last profile for a gateway orphans its device token`() {
        val home = StoredProfile("p1", "Home", "http://GW.local:18789/", lastConnectedAt = null, hasToken = true)
        val lab = StoredProfile("p2", "Lab", "http://lab:18789", lastConnectedAt = null, hasToken = false)
        val homeAgain = StoredProfile("p3", "Home 2", "http://gw.local:18789", lastConnectedAt = null, hasToken = false)
        assertEquals("http://gw.local:18789", orphanedGatewayKey(listOf(home, lab), "p1"))
        assertNull(orphanedGatewayKey(listOf(home, lab, homeAgain), "p1")) // p3 still uses that gateway
        assertNull(orphanedGatewayKey(listOf(home, lab), "missing"))
    }

    @Test fun `toString redacts the token`() {
        assertFalse(token.toString().contains("dt-test-0001"))
    }
}
