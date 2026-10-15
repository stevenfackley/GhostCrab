package com.openclaw.ghostcrab.data.gateway

import com.google.crypto.tink.subtle.Ed25519Verify
import com.openclaw.ghostcrab.testutil.RFC8032_DEVICE_ID
import com.openclaw.ghostcrab.testutil.RFC8032_SEED
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64

class DeviceIdentityTest {

    @Test fun `RFC 8032 test 1 seed yields the RFC public key and its device id`() {
        val identity = DeviceIdentity.fromSeed(RFC8032_SEED)
        assertEquals("11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo", identity.publicKeyBase64Url)
        assertEquals(RFC8032_DEVICE_ID, identity.deviceId)
    }

    @Test fun `RFC 8032 test 1 signature of the empty message`() {
        assertEquals(
            "5VZDAMNgrHKQhuLMgG6CioSHfx645dl02HPgZSJJAVVfuIIVkKM7rMYeOXAc-bRr0lv18FlbviRlUUFDjnoQCw",
            DeviceIdentity.fromSeed(RFC8032_SEED).sign(""),
        )
    }

    @Test fun `device id matches the fingerprint the real gateway accepted`() {
        // device.publicKey and device.id of the connect frame the 2026.10.3 gateway paired (M5 capture).
        assertEquals(
            "f044a1f50388e949137206dbf72f4c79c9b499af0e2a4048b005bbf217a9f2e2",
            DeviceIdentity.deviceIdForPublicKey("e9iojoSOwE3ajNKyeYkso69DO4ByRKR_kJv2IrzMS3Q"),
        )
    }

    @Test fun `generated identities differ and their signatures verify`() {
        val a = DeviceIdentity.generate()
        val b = DeviceIdentity.generate()
        assertNotEquals(a.deviceId, b.deviceId)
        val decoder = Base64.getUrlDecoder()
        Ed25519Verify(decoder.decode(a.publicKeyBase64Url))
            .verify(decoder.decode(a.sign("payload")), "payload".toByteArray())
    }

    @Test fun `toString shows only the device id`() {
        val text = DeviceIdentity.fromSeed(RFC8032_SEED).toString()
        assertEquals("DeviceIdentity(deviceId=$RFC8032_DEVICE_ID)", text)
        assertFalse(text.contains("9d61"))
    }

    @Test fun `a seed that is not 32 bytes is rejected`() {
        assertThrows<IllegalArgumentException> { DeviceIdentity.fromSeed(ByteArray(31)) }
    }
}
