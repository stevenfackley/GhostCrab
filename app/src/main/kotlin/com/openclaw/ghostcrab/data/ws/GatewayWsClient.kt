package com.openclaw.ghostcrab.data.ws

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Thin abstraction over a Ktor WS session — enables injecting a fake in tests. */
interface WsSession {
    suspend fun send(text: String)
    fun incoming(): Flow<String>
    suspend fun close()
}

/**
 * Multiplexed JSON-RPC 2.0 client over a single WS session. Request ids are allocated
 * from a monotonic counter; pending responses are parked as [CompletableDeferred] by id.
 * Notifications (no `id`) are fanned out via [notifications].
 *
 * @param session The underlying [WsSession] carrying raw text frames.
 * @param tokenProvider Supplier for the current bearer token (may return null).
 */
class GatewayWsClient private constructor(
    private val session: WsSession,
    private val tokenProvider: () -> String?,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonRpcResponse>>()
    private val _notifications = MutableSharedFlow<JsonRpcNotification>(extraBufferCapacity = 64)

    /** Hot flow of server-pushed notifications (no `id`). */
    val notifications: Flow<JsonRpcNotification> = _notifications

    private val readerJob: Job = scope.launch {
        session.incoming().collect { raw ->
            dispatch(raw)
        }
    }

    @Suppress("ReturnCount")
    private fun dispatch(raw: String) {
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }
            .onFailure { android.util.Log.d("GatewayWsClient", "unparseable frame: ${it.message}") }
            .getOrNull() ?: return
        if (obj.containsKey("id")) {
            val res = runCatching { json.decodeFromString(JsonRpcResponse.serializer(), raw) }
                .onFailure { android.util.Log.d("GatewayWsClient", "bad response envelope: ${it.message}") }
                .getOrNull() ?: return
            pending.remove(res.id)?.complete(res)
        } else if (obj.containsKey("method")) {
            val n = runCatching { json.decodeFromString(JsonRpcNotification.serializer(), raw) }
                .onFailure { android.util.Log.d("GatewayWsClient", "bad notification: ${it.message}") }
                .getOrNull() ?: return
            scope.launch { _notifications.emit(n) }
        }
    }

    /**
     * Sends a JSON-RPC request and suspends until the matching response arrives.
     *
     * @param method The RPC method name (e.g. `"skills.list"`).
     * @param params Optional JSON parameters.
     * @return The `result` field of the response, or an empty [JsonObject] if absent.
     * @throws WsRpcException if the response contains an `error` object.
     */
    suspend fun request(method: String, params: JsonElement?): JsonElement {
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonRpcResponse>()
        pending[id] = deferred
        val req = JsonRpcRequest(id = id, method = method, params = params)
        @Suppress("TooGenericExceptionCaught")
        try {
            session.send(json.encodeToString(JsonRpcRequest.serializer(), req))
        } catch (e: Throwable) {
            pending.remove(id)
            throw e
        }
        val res = deferred.await()
        res.error?.let { throw WsRpcException(code = it.code, message = it.message) }
        return res.result ?: JsonObject(emptyMap())
    }

    /** Cancels the reader loop, the scope, and closes the underlying session. */
    suspend fun close() {
        readerJob.cancel()
        scope.cancel()
        session.close()
    }

    /** Thrown when the server returns a JSON-RPC error object. */
    class WsRpcException(val code: Int, override val message: String) : RuntimeException(message)

    companion object {
        /**
         * For unit tests only — wires directly to a [FakeWsSession] without Ktor.
         * Production code should construct via DI with a Ktor-backed [WsSession].
         */
        fun forTesting(session: WsSession, tokenProvider: () -> String?): GatewayWsClient =
            GatewayWsClient(session = session, tokenProvider = tokenProvider)
    }
}
