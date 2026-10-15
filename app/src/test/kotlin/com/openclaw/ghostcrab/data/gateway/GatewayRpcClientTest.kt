package com.openclaw.ghostcrab.data.gateway

import com.openclaw.ghostcrab.domain.exception.GatewayConnectionClosedException
import com.openclaw.ghostcrab.domain.exception.GatewayMethodUnsupportedException
import com.openclaw.ghostcrab.domain.exception.GatewayMissingScopeException
import com.openclaw.ghostcrab.domain.exception.GatewayNotConnectedException
import com.openclaw.ghostcrab.domain.exception.GatewayTimeoutException
import com.openclaw.ghostcrab.domain.exception.GatewayUnreachableException
import com.openclaw.ghostcrab.testutil.GatewayFixtures
import com.openclaw.ghostcrab.testutil.ScriptedWsSession
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GatewayRpcClientTest {

    private val endpoint = "ws://test/ws"

    private fun TestScope.client(
        session: ScriptedWsSession,
        timeoutMs: Long = GatewayRpcClient.DEFAULT_REQUEST_TIMEOUT_MS,
    ) = GatewayRpcClient.create(session, endpoint, UnconfinedTestDispatcher(testScheduler), timeoutMs)

    private fun ScriptedWsSession.idOf(method: String): String =
        requireNotNull(sent.first { it.stringOrNull("method") == method }.stringOrNull("id"))

    private fun silent() = ScriptedWsSession(respond = { _, _ -> null })

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

    @Test fun `concurrent requests answered out of order each get their own response`() = runTest {
        val session = silent()
        val rpc = client(session)
        val health = async { rpc.request("health") as JsonObject }
        val models = async { rpc.request("models.list") as JsonObject }
        runCurrent()
        session.reply(session.idOf("models.list"), GatewayFixtures.frame("res-models-list"))
        session.reply(session.idOf("health"), GatewayFixtures.frame("res-health"))
        assertEquals("main", health.await().stringOrNull("defaultAgentId"))
        assertTrue(models.await().containsKey("models"))
    }

    @Test fun `a response with an unknown id is ignored`() = runTest {
        val session = ScriptedWsSession(respond = ScriptedWsSession.fixtures("health" to "res-health"))
        val rpc = client(session)
        session.reply("gc-999", GatewayFixtures.frame("err-missing-scope"))
        assertEquals("main", (rpc.request("health") as JsonObject).stringOrNull("defaultAgentId"))
        assertFalse(rpc.isClosed)
        assertEquals(0, rpc.pendingRequestCount)
    }

    @Test fun `caller cancellation mid-request removes the pending entry`() = runTest {
        val rpc = client(silent())
        val call = async { rpc.request("health") }
        runCurrent()
        assertEquals(1, rpc.pendingRequestCount)
        call.cancelAndJoin()
        assertEquals(0, rpc.pendingRequestCount)
    }

    @Test fun `an outer timeout propagates as cancellation, not as a gateway timeout`() = runTest {
        val rpc = client(ScriptedWsSession(sendChallenge = false, respond = { _, _ -> null }), timeoutMs = 10_000)
        assertThrows<TimeoutCancellationException> { withTimeout(1_000) { rpc.request("health") } }
        assertThrows<TimeoutCancellationException> { withTimeout(1_000) { rpc.awaitChallenge(timeoutMs = 10_000) } }
        assertEquals(0, rpc.pendingRequestCount)
    }

    @Test fun `a shorter request timeout inside a longer outer timeout is a gateway timeout`() = runTest {
        val rpc = client(silent())
        val e = assertThrows<GatewayTimeoutException> {
            withTimeout(10_000) { rpc.request("health", timeoutMs = 1_000) }
        }
        assertEquals("$endpoint#health", e.url)
    }

    @Test fun `close fails every pending request and later ones with the typed error`() = runTest {
        val rpc = client(silent())
        val first = async { runCatching { rpc.request("health") } }
        val second = async { runCatching { rpc.request("status") } }
        runCurrent()
        rpc.close()
        assertInstanceOf(GatewayNotConnectedException::class.java, first.await().exceptionOrNull())
        assertInstanceOf(GatewayNotConnectedException::class.java, second.await().exceptionOrNull())
        assertThrows<GatewayNotConnectedException> { rpc.request("health") }
        assertEquals(0, rpc.pendingRequestCount)
        assertTrue(rpc.isClosed)
    }

    @Test fun `a policy close without a response is a non-retryable close error`() = runTest {
        val session = ScriptedWsSession(sendChallenge = false, respond = { _, _ -> null })
        val rpc = client(session)
        val pending = async { runCatching { rpc.request("health") } }
        runCurrent()
        session.drop(closeCode = 1008, closeReason = "connect required")
        val e = pending.await().exceptionOrNull() as GatewayConnectionClosedException
        assertEquals(1008, e.closeCode)
        assertEquals("connect required", e.closeReason)
        assertFalse(e.isRetryable)
        assertTrue(e.message.orEmpty().contains(endpoint))
        assertThrows<GatewayConnectionClosedException> { rpc.awaitChallenge() }
    }

    @Test fun `events complete when the socket ends`() = runTest {
        val session = silent()
        val rpc = client(session)
        val collected = async { rpc.events.toList().map { it.event } }
        runCurrent()
        session.push(
            buildJsonObject {
                put("type", "event")
                put("event", "skills.changed")
                putJsonObject("payload") {}
            },
        )
        session.drop()
        assertEquals(listOf("skills.changed"), collected.await())
        assertEquals(emptyList<GatewayFrame.Event>(), rpc.events.toList())
    }

    @Test fun `the challenge payload carries the captured nonce`() = runTest {
        val challenge = client(silent()).awaitChallenge().payload as JsonObject
        assertEquals("71d63897-7ebd-44e8-a27a-20ac1598b846", challenge["nonce"]?.jsonPrimitive?.content)
    }
}
