package com.openclaw.ghostcrab.data.gateway

import com.openclaw.ghostcrab.domain.exception.GatewayPairingRequiredException
import com.openclaw.ghostcrab.domain.exception.GatewayRpcException
import com.openclaw.ghostcrab.testutil.RFC8032_SEED
import com.openclaw.ghostcrab.testutil.ScriptedWsSession
import com.openclaw.ghostcrab.testutil.ScriptedWsSession.Companion.fixtures
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ClientIdentityTest {

    private val identity = DeviceIdentity.fromSeed(RFC8032_SEED)

    private fun TestScope.rpc(session: ScriptedWsSession) =
        GatewayRpcClient.create(session, "ws://gw/ws", UnconfinedTestDispatcher(testScheduler))

    @Test fun `the android identity is the allow-listed constant`() {
        val info = ClientInfo.android(version = "9.9.9", locale = "de-DE")
        assertEquals("openclaw-android", info.id)
        assertEquals("ui", info.mode)
        assertEquals("android", info.platform)
        assertNull(info.deviceFamily)
        assertEquals("GhostCrab (Android)", info.displayName)
        assertEquals("9.9.9", info.version)
        assertEquals("de-DE", info.locale)
    }

    @Test fun `the connect frame carries the pinned identity`() = runTest {
        val session = ScriptedWsSession(respond = fixtures("connect" to "hello-ok-paired"))
        val client = ClientInfo.android(version = "0.3.0", locale = "en-US")
        GatewayHandshake.perform(rpc(session), client, ConnectAuth.SharedToken("shared-token"), identity)
        val sent = session.paramsOf("connect").getValue("client").jsonObject
        assertEquals("openclaw-android", sent.getValue("id").jsonPrimitive.content)
        assertEquals("ui", sent.getValue("mode").jsonPrimitive.content)
        assertEquals("android", sent.getValue("platform").jsonPrimitive.content)
        assertNull(sent["deviceFamily"])
    }

    @Test fun `an id outside the allow-list is an RPC rejection shown verbatim`() = runTest {
        val session = ScriptedWsSession(
            closeAfterReplyTo = setOf("connect"),
            respond = fixtures("connect" to "err-client-id-invalid"),
        )
        val client = ClientInfo.android(version = "0.3.0").copy(id = "ghostcrab-android")
        val e = assertThrows<GatewayRpcException> {
            GatewayHandshake.perform(rpc(session), client, ConnectAuth.SharedToken("shared-token"), identity)
        }
        assertEquals("INVALID_REQUEST", e.code)
        assertTrue(e.rpcMessage.contains("/client/id"))
    }

    @Test fun `a platform change on a paired device asks for re-approval`() = runTest {
        val session = ScriptedWsSession(
            closeAfterReplyTo = setOf("connect"),
            respond = fixtures("connect" to "err-pairing-metadata-upgrade"),
        )
        val client = ClientInfo.android(version = "0.3.0")
        val e = assertThrows<GatewayPairingRequiredException> {
            GatewayHandshake.perform(rpc(session), client, ConnectAuth.SharedToken("shared-token"), identity)
        }
        assertEquals("metadata-upgrade", e.reason)
        assertEquals("6837c6b0-f559-445b-bc0c-a50fbe5b5998", e.requestId)
    }
}
