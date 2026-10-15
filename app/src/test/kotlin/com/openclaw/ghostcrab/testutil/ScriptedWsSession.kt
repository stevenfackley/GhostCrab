package com.openclaw.ghostcrab.testutil

import com.openclaw.ghostcrab.data.ws.WsCloseReason
import com.openclaw.ghostcrab.data.ws.WsSession
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fake gateway socket. Emits the captured `connect.challenge` on open (unless [sendChallenge] is false),
 * then answers each request with the frame [respond] returns for its method; the reply's `id` is rewritten
 * to the request's id. A `null` reply means "never answer". Methods in [closeAfterReplyTo] end the socket
 * after the reply, as the real gateway does after a failed `connect` (close 1008).
 */
class ScriptedWsSession(
    sendChallenge: Boolean = true,
    private val closeAfterReplyTo: Set<String> = emptySet(),
    private val respond: (method: String, params: JsonObject) -> JsonObject?,
) : WsSession {

    private val inbound = Channel<String>(Channel.UNLIMITED)
    private val sentFrames = CopyOnWriteArrayList<JsonObject>()

    /** Every frame the client sent, parsed, in order. */
    val sent: List<JsonObject> get() = sentFrames.toList()

    /** `true` once the client closed the session. */
    @Volatile var closedByClient: Boolean = false
        private set

    @Volatile private var serverClose: WsCloseReason? = null

    init {
        if (sendChallenge) inbound.trySend(GatewayFixtures.text("challenge"))
    }

    /** The `params` of the first request for [method] (empty object when it had none). */
    fun paramsOf(method: String): JsonObject =
        sentFrames.first { it["method"]?.jsonPrimitive?.content == method }["params"] as? JsonObject
            ?: JsonObject(emptyMap())

    override suspend fun send(text: String) {
        val request = Json.parseToJsonElement(text).jsonObject
        sentFrames.add(request)
        val method = request.getValue("method").jsonPrimitive.content
        val reply = respond(method, request["params"] as? JsonObject ?: JsonObject(emptyMap())) ?: return
        inbound.trySend(JsonObject(reply + ("id" to request.getValue("id"))).toString())
        if (method in closeAfterReplyTo) inbound.close()
    }

    /** Delivers a server-pushed frame. */
    fun push(frame: JsonObject) {
        inbound.trySend(frame.toString())
    }

    /** Delivers [frame] as the answer to the request with [requestId] (for out-of-order or late replies). */
    fun reply(requestId: String, frame: JsonObject) {
        push(JsonObject(frame + ("id" to JsonPrimitive(requestId))))
    }

    /**
     * Simulates the gateway ending the socket.
     *
     * @param closeCode The close frame's code (e.g. 1008), or `null` for a drop without a close frame.
     * @param closeReason The close frame's reason text.
     */
    fun drop(closeCode: Int? = null, closeReason: String = "") {
        serverClose = closeCode?.let { WsCloseReason(it, closeReason) }
        inbound.close()
    }

    override fun incoming(): Flow<String> = inbound.receiveAsFlow()

    override suspend fun closeReason(): WsCloseReason? = serverClose

    override suspend fun close() {
        closedByClient = true
        inbound.close()
    }

    companion object {
        /** A responder answering each listed method with the named fixture frame. */
        fun fixtures(vararg byMethod: Pair<String, String>): (String, JsonObject) -> JsonObject? {
            val names = byMethod.toMap()
            return { method, _ -> names[method]?.let { GatewayFixtures.frame(it) } }
        }
    }
}
