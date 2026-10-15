package com.openclaw.ghostcrab.data.gateway

import com.openclaw.ghostcrab.domain.exception.GatewayMethodUnsupportedException
import com.openclaw.ghostcrab.domain.exception.GatewayMissingScopeException
import com.openclaw.ghostcrab.domain.exception.GatewayTimeoutException
import com.openclaw.ghostcrab.domain.exception.GatewayUnreachableException
import com.openclaw.ghostcrab.testutil.ScriptedWsSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GatewayRpcClientTest {

    private val endpoint = "ws://test/ws"

    private fun client(session: ScriptedWsSession, timeoutMs: Long = GatewayRpcClient.DEFAULT_REQUEST_TIMEOUT_MS) =
        GatewayRpcClient.create(session, endpoint, Dispatchers.Unconfined, timeoutMs)

    @Test fun `request correlates the response by string id`() = runTest {
        val session = ScriptedWsSession(respond = ScriptedWsSession.fixtures("health" to "res-health"))
        val payload = client(session).request("health") as JsonObject
        assertEquals("true", payload["ok"].toString())
        val sent = session.sent.single()
        assertEquals("req", sent.stringOrNull("type"))
        assertTrue(sent.stringOrNull("id").orEmpty().startsWith("gc-"))
        assertEquals(JsonObject(emptyMap()), sent["params"])
    }

    @Test fun `ok false becomes the typed exception`() = runTest {
        val session = ScriptedWsSession(
            respond = ScriptedWsSession.fixtures(
                "skills.list" to "err-unknown-method",
                "status" to "err-missing-scope",
            ),
        )
        val rpc = client(session)
        assertThrows<GatewayMethodUnsupportedException> { rpc.request("skills.list") }
        assertEquals("operator.read", assertThrows<GatewayMissingScopeException> { rpc.request("status") }.missingScope)
    }

    @Test fun `a challenge that arrived before awaitChallenge is still returned`() = runTest {
        val rpc = client(ScriptedWsSession(respond = { _, _ -> null }))
        assertEquals(GatewayRpcClient.CONNECT_CHALLENGE_EVENT, rpc.awaitChallenge().event)
    }

    @Test fun `no challenge times out with a typed error`() = runTest {
        val rpc = client(ScriptedWsSession(sendChallenge = false, respond = { _, _ -> null }))
        val e = assertThrows<GatewayTimeoutException> { rpc.awaitChallenge(timeoutMs = 500) }
        assertEquals("$endpoint#connect.challenge", e.url)
    }

    @Test fun `an unanswered request times out`() = runTest {
        val rpc = client(ScriptedWsSession(respond = { _, _ -> null }), timeoutMs = 1_000)
        assertEquals("$endpoint#health", assertThrows<GatewayTimeoutException> { rpc.request("health") }.url)
    }

    @Test fun `a dropped socket fails the pending request and every later one`() = runTest {
        val session = ScriptedWsSession(respond = { _, _ -> null })
        val rpc = client(session)
        val pending = async { runCatching { rpc.request("health") } }
        runCurrent()
        session.drop()
        assertInstanceOf(GatewayUnreachableException::class.java, pending.await().exceptionOrNull())
        assertThrows<GatewayUnreachableException> { rpc.request("status") }
        assertTrue(rpc.isClosed)
    }

    @Test fun `server events reach collectors`() = runTest {
        val session = ScriptedWsSession(respond = { _, _ -> null })
        val rpc = client(session)
        val event = async { rpc.events.first { it.event == "skills.changed" } }
        runCurrent()
        session.push(
            buildJsonObject {
                put("type", "event")
                put("event", "skills.changed")
                putJsonObject("payload") {}
            },
        )
        assertEquals("skills.changed", event.await().event)
    }

    @Test fun `close closes the underlying socket`() = runTest {
        val session = ScriptedWsSession(respond = { _, _ -> null })
        client(session).close()
        assertTrue(session.closedByClient)
    }
}
