package com.openclaw.ghostcrab.data.gateway

import com.openclaw.ghostcrab.domain.exception.GatewayConnectAuthException
import com.openclaw.ghostcrab.domain.exception.GatewayPairingRequiredException
import com.openclaw.ghostcrab.domain.exception.GatewayProtocolException
import com.openclaw.ghostcrab.domain.model.AuthRequirement
import com.openclaw.ghostcrab.testutil.GatewayFixtures
import com.openclaw.ghostcrab.testutil.OPERATOR_SCOPES
import com.openclaw.ghostcrab.testutil.RFC8032_DEVICE_ID
import com.openclaw.ghostcrab.testutil.RFC8032_SEED
import com.openclaw.ghostcrab.testutil.ScriptedWsSession
import com.openclaw.ghostcrab.testutil.ScriptedWsSession.Companion.fixtures
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GatewayHandshakeTest {

    private val identity = DeviceIdentity.fromSeed(RFC8032_SEED)
    private val client = ClientInfo(
        id = "openclaw-android",
        version = "0.3.0",
        platform = "android",
        displayName = "GhostCrab (Android)",
    )

    private fun TestScope.rpc(session: ScriptedWsSession) =
        GatewayRpcClient.create(session, "ws://gw/ws", UnconfinedTestDispatcher(testScheduler))

    @Test fun `perform signs the challenge and parses hello-ok`() = runTest {
        val session = ScriptedWsSession(respond = fixtures("connect" to "hello-ok-paired"))
        val hello = GatewayHandshake.perform(rpc(session), client, ConnectAuth.SharedToken("shared-token"), identity)

        assertEquals("2026.10.3", hello.serverVersion)
        assertEquals(OPERATOR_SCOPES.toSet(), hello.scopes)
        assertEquals("dt-test-0001", hello.deviceToken)
        assertEquals("token", hello.gatewayAuthMode)
        assertEquals("ecb0a0611ff5", hello.gatewayHost)
        assertTrue("skills.status" in hello.methods)

        val params = session.paramsOf("connect")
        assertEquals(4, params.getValue("minProtocol").jsonPrimitive.content.toInt())
        assertEquals("operator", params.getValue("role").jsonPrimitive.content)
        assertEquals("shared-token", params.getValue("auth").jsonObject.getValue("token").jsonPrimitive.content)
        assertEquals("ghostcrab-android/0.3.0", params.getValue("userAgent").jsonPrimitive.content)
        val device = params.getValue("device").jsonObject
        assertEquals(RFC8032_DEVICE_ID, device.getValue("id").jsonPrimitive.content)
        assertEquals("71d63897-7ebd-44e8-a27a-20ac1598b846", device.getValue("nonce").jsonPrimitive.content)
        assertEquals("1792027507354", device.getValue("signedAt").jsonPrimitive.content)
        // Same payload and key as DeviceAuthPayloadTest: Tink's Ed25519 is deterministic.
        assertEquals(
            "GzOBsHYT5BEZyxVwIPScWWlbTEymYQ8Pf5XPg9loqDJwoJraqVIhPeZa2Y1c8dWBprteYbjNT-DpqDXjVKEyBg",
            device.getValue("signature").jsonPrimitive.content,
        )
    }

    @Test fun `a stored device token is sent as deviceToken with its own scopes`() = runTest {
        val session = ScriptedWsSession(respond = fixtures("connect" to "hello-ok-paired"))
        val auth = ConnectAuth.DeviceToken("dt-old", listOf("operator.read"))
        GatewayHandshake.perform(rpc(session), client, auth, identity)
        val params = session.paramsOf("connect")
        assertEquals("dt-old", params.getValue("auth").jsonObject.getValue("deviceToken").jsonPrimitive.content)
        assertEquals(listOf("operator.read"), params.getValue("scopes").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun `no credential sends no auth block`() = runTest {
        val none = ScriptedWsSession(respond = fixtures("connect" to "hello-ok-paired"))
        GatewayHandshake.perform(rpc(none), client, ConnectAuth.None, identity)
        assertNull(none.paramsOf("connect")["auth"])
    }

    @Test fun `pairing required and token mismatch surface as typed errors`() = runTest {
        val pairing = ScriptedWsSession(
            closeAfterReplyTo = setOf("connect"),
            respond = fixtures("connect" to "err-pairing-required"),
        )
        assertEquals(
            "af0b5d81-7bd2-4b86-80b0-4a489b541974",
            assertThrows<GatewayPairingRequiredException> {
                GatewayHandshake.perform(rpc(pairing), client, ConnectAuth.SharedToken("shared-token"), identity)
            }.requestId,
        )
        val mismatch = ScriptedWsSession(
            closeAfterReplyTo = setOf("connect"),
            respond = fixtures("connect" to "err-token-mismatch"),
        )
        assertEquals(
            "AUTH_TOKEN_MISMATCH",
            assertThrows<GatewayConnectAuthException> {
                GatewayHandshake.perform(rpc(mismatch), client, ConnectAuth.SharedToken("wrong"), identity)
            }.detailCode,
        )
    }

    @Test fun `a token-only hello has no scopes`() {
        val payload = GatewayFixtures.frame("hello-ok-noscope").getValue("payload")
        val hello = GatewayHandshake.parseHello("ws://gw/ws", payload)
        assertTrue(hello.scopes.isEmpty())
        assertNull(hello.gatewayAuthMode)
        assertNull(hello.gatewayHost)
    }

    @Test fun `a challenge without ts is a protocol error`() = runTest {
        val session = ScriptedWsSession(sendChallenge = false, respond = { _, _ -> null })
        session.push(
            buildJsonObject {
                put("type", "event")
                put("event", "connect.challenge")
                putJsonObject("payload") { put("nonce", "n") }
            },
        )
        assertThrows<GatewayProtocolException> {
            GatewayHandshake.perform(rpc(session), client, ConnectAuth.None, identity)
        }
    }

    @Test fun `auth requirement and capabilities come from hello-ok`() {
        val payload = GatewayFixtures.frame("hello-ok-paired").getValue("payload")
        val hello = GatewayHandshake.parseHello("ws://gw/ws", payload)
        assertEquals(AuthRequirement.Token, hello.toAuthRequirement(token = null))
        assertEquals(AuthRequirement.None, hello.copy(gatewayAuthMode = "none").toAuthRequirement(token = "x"))
        val silent = hello.copy(gatewayAuthMode = null, authMethod = null)
        assertEquals(AuthRequirement.None, silent.toAuthRequirement(null))
        assertEquals(
            listOf(CAPABILITY_CONFIG, CAPABILITY_MODELS, CAPABILITY_SKILLS),
            gatewayCapabilities(hello.methods, hello.scopes),
        )
    }

    @Test fun `capabilities follow the granted scopes, not just the method list`() {
        val methods = GatewayHandshake.parseHello(
            "ws://gw/ws",
            GatewayFixtures.frame("hello-ok-noscope").getValue("payload"),
        ).methods
        assertEquals(emptyList<String>(), gatewayCapabilities(methods, emptySet()))
        assertEquals(
            listOf(CAPABILITY_MODELS, CAPABILITY_SKILLS),
            gatewayCapabilities(methods, setOf("operator.read", "operator.write")),
        )
        assertEquals(listOf(CAPABILITY_CONFIG), gatewayCapabilities(methods, setOf("operator.admin")))
        assertEquals(emptyList<String>(), gatewayCapabilities(setOf("health"), OPERATOR_SCOPES.toSet()))
    }

    @Test fun `credential selection follows upstream order without setup-code tokens`() {
        val stored = StoredDeviceToken("dt", listOf("operator.read"), "operator", RFC8032_DEVICE_ID)
        assertTrue(selectConnectAuth("t", stored) is ConnectAuth.SharedToken)
        assertTrue(selectConnectAuth(null, stored) is ConnectAuth.DeviceToken)
        assertEquals(ConnectAuth.None, selectConnectAuth(null, null))
    }

    @Test fun `hello-ok and credentials never print their secrets`() {
        val payload = GatewayFixtures.frame("hello-ok-paired").getValue("payload")
        val hello = GatewayHandshake.parseHello("ws://gw/ws", payload)
        assertFalse(hello.toString().contains("dt-test-0001"), hello.toString())
        assertFalse(ConnectAuth.SharedToken("shared-token").toString().contains("shared-token"))
        assertFalse(ConnectAuth.DeviceToken("dt-old", listOf("operator.read")).toString().contains("dt-old"))
    }
}
