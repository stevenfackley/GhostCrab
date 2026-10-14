package com.openclaw.ghostcrab.data.ws

import com.openclaw.ghostcrab.domain.exception.GatewayTimeoutException
import com.openclaw.ghostcrab.domain.exception.GatewayUnreachableException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

class FakeWsSession : WsSession {
    private val inbound = MutableSharedFlow<String>(extraBufferCapacity = 16)
    private val outbound = Channel<String>(Channel.UNLIMITED)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun send(text: String) { outbound.send(text) }
    override fun incoming(): Flow<String> = inbound
    override suspend fun close() = Unit

    suspend fun awaitSent(): JsonObject =
        json.decodeFromString(JsonObject.serializer(), outbound.receive())

    suspend fun pushInbound(text: String) { inbound.emit(text) }
}

class GatewayWsClientTest {

    @Test
    fun `request returns matching response by id`() = runTest {
        val fakeSession = FakeWsSession()
        val client = GatewayWsClient.forTesting(
            session = fakeSession,
        )

        val deferred = async {
            client.request(
                method = "skills.list",
                params = buildJsonObject { },
            )
        }

        val sent = fakeSession.awaitSent()
        val id = sent["id"]!!.toString().toLong()
        fakeSession.pushInbound("""{"jsonrpc":"2.0","id":$id,"result":{"skills":[]}}""")

        val result = deferred.await() as JsonObject
        assertEquals("[]", result["skills"].toString())
    }

    @Test
    fun `request throws WsRpcException when response carries error`() = runTest {
        val fakeSession = FakeWsSession()
        val client = GatewayWsClient.forTesting(
            session = fakeSession,
        )

        val deferred = async {
            runCatching { client.request("skills.install", buildJsonObject {}) }
        }
        val sent = fakeSession.awaitSent()
        val id = sent["id"]!!.toString().toLong()
        fakeSession.pushInbound("""{"jsonrpc":"2.0","id":$id,"error":{"code":-32003,"message":"missing scope"}}""")

        val result = deferred.await()
        val thrown = result.exceptionOrNull() as GatewayWsClient.WsRpcException
        assertEquals(-32003, thrown.code)
        assertEquals("missing scope", thrown.message)
    }

    @Test
    fun `request fails with GatewayTimeoutException when no response arrives`() = runTest {
        val client = GatewayWsClient.forTesting(FakeWsSession(), requestTimeoutMs = 1_000)

        assertThrows<GatewayTimeoutException> { client.request("skills.list", null) }
    }

    @Test
    fun `pending request fails with GatewayUnreachableException when the socket closes`() = runTest {
        val session = ClosingWsSession()
        val client = GatewayWsClient.forTesting(session)
        val call = async { runCatching { client.request("skills.list", null) } }
        session.sent.receive()

        session.closeFromServer()

        assertInstanceOf(GatewayUnreachableException::class.java, call.await().exceptionOrNull())
    }

    @Test
    fun `transport error fails pending requests with the error as cause`() = runTest {
        val session = ClosingWsSession()
        val client = GatewayWsClient.forTesting(session)
        val call = async { runCatching { client.request("skills.list", null) } }
        session.sent.receive()

        session.closeFromServer(IOException("connection reset"))

        val failure = assertInstanceOf(GatewayUnreachableException::class.java, call.await().exceptionOrNull())
        assertInstanceOf(IOException::class.java, failure.cause)
    }
}

/** Session whose inbound stream can be completed, like a server closing the socket. */
private class ClosingWsSession : WsSession {
    private val inbound = Channel<String>(Channel.UNLIMITED)
    val sent = Channel<String>(Channel.UNLIMITED)
    override suspend fun send(text: String) { sent.send(text) }
    override fun incoming(): Flow<String> = inbound.consumeAsFlow()
    override suspend fun close() = Unit
    fun closeFromServer(cause: Throwable? = null) { inbound.close(cause) }
}
