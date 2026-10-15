package com.openclaw.ghostcrab.data.storage

import android.content.Context
import android.content.SharedPreferences
import com.openclaw.ghostcrab.domain.exception.DeviceCredentialStorageException
import com.openclaw.ghostcrab.testutil.InMemorySharedPreferences
import com.openclaw.ghostcrab.testutil.RFC8032_DEVICE_ID
import com.openclaw.ghostcrab.testutil.RFC8032_SEED
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.KeyStoreException
import java.util.Base64

class DeviceIdentityStoreTest {

    private val context = mockk<Context>(relaxed = true)
    private val warnings = mutableListOf<String>()

    @Test fun `first use generates and persists a seed that a new store reloads`() = runTest {
        val prefs = InMemorySharedPreferences()
        val io = StandardTestDispatcher(testScheduler)
        val firstStore = DeviceIdentityStore(context, io, warnings::add) { prefs }
        val first = firstStore.identity()
        assertTrue(prefs.contains(DeviceIdentityStore.KEY_SEED))
        val reloaded = DeviceIdentityStore(context, io, warnings::add) { prefs }.identity()
        assertEquals(first.deviceId, reloaded.deviceId)
        assertFalse(firstStore.regenerated.value) // a first install is not a regeneration
        assertTrue(warnings.isEmpty())
    }

    @Test fun `the identity is cached per store`() = runTest {
        val store = DeviceIdentityStore(context, StandardTestDispatcher(testScheduler)) { InMemorySharedPreferences() }
        assertSame(store.identity(), store.identity())
    }

    @Test fun `a seed of the wrong length is replaced`() = runTest {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putString(DeviceIdentityStore.KEY_SEED, Base64.getEncoder().encodeToString(ByteArray(5))).commit()
        val store = DeviceIdentityStore(context, StandardTestDispatcher(testScheduler), warnings::add) { prefs }
        val identity = store.identity()
        val stored = Base64.getDecoder().decode(prefs.getString(DeviceIdentityStore.KEY_SEED, null))
        assertEquals(32, stored.size)
        assertEquals(64, identity.deviceId.length)
        assertTrue(store.regenerated.value)
        assertEquals(1, warnings.size)
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
        val storeA = DeviceIdentityStore(context, StandardTestDispatcher(testScheduler), warnings::add) { prefs }
        val a = storeA.identity()
        val b = DeviceIdentityStore(context, StandardTestDispatcher(testScheduler), warnings::add) { prefs }.identity()
        verify { editor.remove(DeviceIdentityStore.KEY_SEED) }
        assertNotEquals(a.deviceId, b.deviceId) // nothing readable was persisted, so each store starts over
        assertTrue(storeA.regenerated.value)
        assertTrue(warnings.all { "SecurityException" in it && "keystore key lost" !in it })
    }

    @Test fun `a transient open failure is retried once and keeps the stored identity`() = runTest {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putString(DeviceIdentityStore.KEY_SEED, Base64.getEncoder().encodeToString(RFC8032_SEED)).commit()
        var opens = 0
        val store = DeviceIdentityStore(context, StandardTestDispatcher(testScheduler), warnings::add) {
            opens++
            if (opens == 1) throw KeyStoreException("keystore busy") else prefs
        }
        assertEquals(RFC8032_DEVICE_ID, store.identity().deviceId)
        assertEquals(2, opens)
        assertFalse(store.regenerated.value)
        verify(exactly = 0) { context.deleteSharedPreferences(any()) }
    }

    @Test fun `an identity file the Keystore can no longer open is deleted and recreated`() = runTest {
        val fresh = InMemorySharedPreferences()
        var opens = 0
        val store = DeviceIdentityStore(context, StandardTestDispatcher(testScheduler), warnings::add) {
            opens++
            if (opens <= 2) throw KeyStoreException("wrapped _androidx_security_master_key_ lost") else fresh
        }
        val identity = store.identity()
        verify { context.deleteSharedPreferences("ghostcrab_device_identity") }
        assertTrue(fresh.contains(DeviceIdentityStore.KEY_SEED))
        assertTrue(store.regenerated.value)
        assertSame(identity, store.identity())
        assertEquals(3, opens)
        assertEquals(1, warnings.size)
        assertTrue("KeyStoreException" in warnings.single())
        assertFalse("master_key" in warnings.single()) // class name only, never the exception message
    }

    @Test fun `an identity file that cannot be recreated fails with a typed error`() = runTest {
        val store = DeviceIdentityStore(context, StandardTestDispatcher(testScheduler), warnings::add) {
            throw KeyStoreException("keystore offline")
        }
        val error = assertThrows<DeviceCredentialStorageException> { store.identity() }
        assertTrue(error.cause is KeyStoreException)
        assertTrue(error.isRetryable)
    }
}
