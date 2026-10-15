package com.openclaw.ghostcrab.data.gateway

import com.openclaw.ghostcrab.testutil.GatewayFixtures
import com.openclaw.ghostcrab.testutil.OPERATOR_SCOPES
import com.openclaw.ghostcrab.testutil.RFC8032_SEED
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class DeviceAuthPayloadTest {

    private fun payload(
        token: String? = "shared-token",
        platform: String = "android",
        deviceFamily: String? = null,
    ) = DeviceAuthPayload(
        deviceId = "f044a1f50388e949137206dbf72f4c79c9b499af0e2a4048b005bbf217a9f2e2",
        clientId = "openclaw-android",
        clientMode = "ui",
        role = "operator",
        scopes = OPERATOR_SCOPES,
        signedAtMs = 1792027489701,
        token = token,
        nonce = "80f21d17-5553-4b86-b6bf-5bbddbc4f807",
        platform = platform,
        deviceFamily = deviceFamily,
    )

    @Test fun `v3 layout matches the frames the 2026_10_3 gateway accepted`() {
        // Fields of the M5 connect frame (ws-device-1-unpaired); the QA token is replaced by shared-token.
        assertEquals(
            "v3|f044a1f50388e949137206dbf72f4c79c9b499af0e2a4048b005bbf217a9f2e2|openclaw-android|ui|operator|" +
                "operator.read,operator.write,operator.admin|1792027489701|shared-token|" +
                "80f21d17-5553-4b86-b6bf-5bbddbc4f807|android|",
            payload().v3(),
        )
    }

    @Test fun `platform and device family are trimmed and ASCII-lowercased like upstream`() {
        assertEquals(true, payload(platform = " Android ", deviceFamily = "Pixel").v3().endsWith("|android|pixel"))
    }

    @Test fun `no token signs as an empty field`() {
        assertEquals(true, payload(token = null).v3().contains("|1792027489701||80f21d17-"))
    }

    @Test fun `signature for a fixed seed matches Node's Ed25519`() {
        // Node 26: crypto.sign(null, Buffer.from(payload), rfc8032Test1Key).toString("base64url").
        val identity = DeviceIdentity.fromSeed(RFC8032_SEED)
        val signed = DeviceAuthPayload(
            deviceId = identity.deviceId, clientId = "openclaw-android", clientMode = "ui", role = "operator",
            scopes = OPERATOR_SCOPES, signedAtMs = 1792027507354, token = "shared-token",
            nonce = "71d63897-7ebd-44e8-a27a-20ac1598b846", platform = "android", deviceFamily = null,
        ).v3()
        assertEquals(
            "GzOBsHYT5BEZyxVwIPScWWlbTEymYQ8Pf5XPg9loqDJwoJraqVIhPeZa2Y1c8dWBprteYbjNT-DpqDXjVKEyBg",
            identity.sign(signed),
        )
    }

    @Test fun `payload replayed from the captured challenge and pairing frames signs like Node`() {
        // Nonce and ts come from the real connect.challenge, the scopes from the gateway's echo of the request.
        val challenge = GatewayFixtures.frame("challenge").getValue("payload").jsonObject
        val details = GatewayFixtures.frame("err-pairing-required").getValue("error").jsonObject
            .getValue("details").jsonObject
        val identity = DeviceIdentity.fromSeed(RFC8032_SEED)
        val signed = DeviceAuthPayload(
            deviceId = identity.deviceId,
            clientId = "openclaw-android",
            clientMode = "ui",
            role = details.getValue("requestedRole").jsonPrimitive.content,
            scopes = details.getValue("requestedScopes").jsonArray.map { it.jsonPrimitive.content },
            signedAtMs = challenge.getValue("ts").jsonPrimitive.long,
            token = "shared-token",
            nonce = challenge.getValue("nonce").jsonPrimitive.content,
            platform = "android",
            deviceFamily = null,
        ).v3()
        assertEquals(
            "GzOBsHYT5BEZyxVwIPScWWlbTEymYQ8Pf5XPg9loqDJwoJraqVIhPeZa2Y1c8dWBprteYbjNT-DpqDXjVKEyBg",
            identity.sign(signed),
        )
    }

    @Test fun `toString redacts the token`() {
        assertFalse(payload().toString().contains("shared-token"))
    }
}
