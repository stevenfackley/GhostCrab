package com.openclaw.ghostcrab.data.storage

import android.content.Context
import android.content.SharedPreferences
import com.openclaw.ghostcrab.testutil.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class DeviceIdentityStoreTest {

    private val context = mockk<Context>(relaxed = true)

    @Test fun `first use generates and persists a seed that a new store reloads`() = runTest {
        val prefs = InMemorySharedPreferences()
        val io = StandardTestDispatcher(testScheduler)
        val first = DeviceIdentityStore(context, io) { prefs }.identity()
        assertTrue(prefs.contains(DeviceIdentityStore.KEY_SEED))
        val reloaded = DeviceIdentityStore(context, io) { prefs }.identity()
        assertEquals(first.deviceId, reloaded.deviceId)
    }

    @Test fun `the identity is cached per store`() = runTest {
        val store = DeviceIdentityStore(context, StandardTestDispatcher(testScheduler)) { InMemorySharedPreferences() }
        assertSame(store.identity(), store.identity())
    }

    @Test fun `a seed of the wrong length is replaced`() = runTest {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putString(DeviceIdentityStore.KEY_SEED, Base64.getEncoder().encodeToString(ByteArray(5))).commit()
        val identity = DeviceIdentityStore(context, StandardTestDispatcher(testScheduler)) { prefs }.identity()
        val stored = Base64.getDecoder().decode(prefs.getString(DeviceIdentityStore.KEY_SEED, null))
        assertEquals(32, stored.size)
        assertEquals(64, identity.deviceId.length)
    }

    @Test fun `an unreadable seed (Keystore reset) is cleared and replaced`() = runTest {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true) {
            every { remove(any()) } returns this
            every { putString(any(), any()) } returns this
            every { commit() } returns true
        }
        val prefs = mockk<SharedPreferences> {
            every { getString(DeviceIdentityStore.KEY_SEED, null) } throws SecurityException("keystore key lost")
            every { edit() } returns editor
        }
        val a = DeviceIdentityStore(context, StandardTestDispatcher(testScheduler)) { prefs }.identity()
        val b = DeviceIdentityStore(context, StandardTestDispatcher(testScheduler)) { prefs }.identity()
        verify { editor.remove(DeviceIdentityStore.KEY_SEED) }
        assertNotEquals(a.deviceId, b.deviceId) // nothing readable was persisted, so each store starts over
    }
}
