package com.openclaw.ghostcrab.data.gateway

import android.content.Context
import com.openclaw.ghostcrab.data.storage.ConnectionProfileStore
import com.openclaw.ghostcrab.testutil.InMemorySharedPreferences
import com.openclaw.ghostcrab.testutil.OPERATOR_SCOPES
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DeviceTokenStoreTest {

    private val prefs = InMemorySharedPreferences()
    private val token = StoredDeviceToken("dt-test-0001", OPERATOR_SCOPES, "operator", "device-1")

    private fun TestScope.store() =
        ConnectionProfileStore(mockk<Context>(relaxed = true), StandardTestDispatcher(testScheduler)) { prefs }

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

    @Test fun `toString redacts the token`() {
        assertFalse(token.toString().contains("dt-test-0001"))
    }
}
