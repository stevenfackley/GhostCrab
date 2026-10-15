package com.openclaw.ghostcrab.data.ws

import com.openclaw.ghostcrab.domain.exception.GatewayException
import com.openclaw.ghostcrab.domain.exception.GatewayTimeoutException
import com.openclaw.ghostcrab.domain.exception.GatewayUnreachableException
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

/** Thin abstraction over a Ktor WS session — enables injecting a fake in tests. */
interface WsSession {
    suspend fun send(text: String)
    fun incoming(): Flow<String>
    suspend fun close()

    /**
     * Call once [incoming] has completed.
     *
     * @return The close frame the server ended the socket with, or `null` when it ended without one or the
     *   implementation cannot tell.
     * @throws Exception the transport failure, when the socket failed instead of closing.
     */
    suspend fun closeReason(): WsCloseReason? = null
}

/**
 * The server's WebSocket close frame (RFC 6455 §7.4).
 *
 * @param code Close code, e.g. 1008 (policy violation).
 * @param reason Close reason text; may be empty.
 */
data class WsCloseReason(val code: Int, val reason: String)

/**
 * Multiplexed JSON-RPC 2.0 client over a single WS session. Request ids come from a monotonic
 * counter; pending responses are parked as [CompletableDeferred] by id. Notifications (no `id`)
 * are fanned out via [notifications].
 *
 * Every [request] is bounded by a timeout, and when the socket closes all pending requests fail
 * with [GatewayUnreachableException] instead of hanging. Construct with [create] or [forTesting].
 */
class GatewayWsClient private constructor(
    private val session: WsSession,
    private val endpoint: String,
    private val requestTimeoutMs: Long,
    dispatcher: CoroutineContext,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonRpcResponse>>()
    private val _notifications = MutableSharedFlow<JsonRpcNotification>(extraBufferCapacity = 64)

    /** Set once the socket has ended, before parked requests are failed, so later requests fail fast. */
    @Volatile private var closedCause: GatewayUnreachableException? = null

    /** Hot flow of server-pushed notifications (no `id`). */
    val notifications: Flow<JsonRpcNotification> = _notifications

    private val readerJob: Job = scope.launch {
        var transportError: Exception? = null
        try {
            session.incoming().collect { raw -> dispatch(raw) }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // A transport error ends the socket like a close does. It must not escape this scope:
            // there is no handler, so it would reach the default uncaught-exception handler.
            transportError = e
        } finally {
            // Socket closed (or client closed): nobody will ever answer the parked requests.
            val cause = GatewayUnreachableException(endpoint, transportError)
            closedCause = cause
            failPending(cause)
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
     * @throws WsRpcException if the response carries an `error` object.
     * @throws GatewayTimeoutException if no response arrives within the request timeout.
     * @throws GatewayUnreachableException if the request could not be sent, or the socket closed
     *   before the response arrived (immediately when it had already closed).
     */
    suspend fun request(method: String, params: JsonElement?): JsonElement {
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonRpcResponse>()
        pending[id] = deferred
        // Registered after the reader failed the parked requests: fail now instead of timing out.
        closedCause?.let { deferred.completeExceptionally(it) }
        val res = try {
            if (!deferred.isCompleted) sendRequest(JsonRpcRequest(id = id, method = method, params = params))
            withTimeout(requestTimeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            throw GatewayTimeoutException("$endpoint#$method", e)
        } finally {
            pending.remove(id)
        }
        res.error?.let { throw WsRpcException(code = it.code, message = it.message) }
        return res.result ?: JsonObject(emptyMap())
    }

    private suspend fun sendRequest(req: JsonRpcRequest) {
        try {
            session.send(json.encodeToString(JsonRpcRequest.serializer(), req))
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            throw GatewayUnreachableException(endpoint, e)
        }
    }

    /** Cancels the reader loop and the scope, then closes the underlying session. */
    suspend fun close() {
        readerJob.cancel()
        scope.cancel()
        session.close()
    }

    private fun failPending(cause: GatewayException) {
        pending.values.forEach { it.completeExceptionally(cause) }
        pending.clear()
    }

    /** Thrown when the server returns a JSON-RPC error object. */
    class WsRpcException(val code: Int, override val message: String) : RuntimeException(message)

    companion object {
        /** Time to wait for a JSON-RPC response before failing with [GatewayTimeoutException]. */
        const val DEFAULT_REQUEST_TIMEOUT_MS = 30_000L

        /**
         * Production factory — reader loop on [Dispatchers.Default].
         *
         * @param session A connected WS session.
         * @param endpoint The `ws(s)://…/ws` URL, used in error messages.
         * @return A client that owns [session]; call [close] when done.
         */
        fun create(session: WsSession, endpoint: String): GatewayWsClient =
            GatewayWsClient(session, endpoint, DEFAULT_REQUEST_TIMEOUT_MS, Dispatchers.Default)

        /**
         * For unit tests only — deterministic frame dispatch on [Dispatchers.Unconfined] by default.
         *
         * @param session Fake session.
         * @param dispatcher Context for the reader loop.
         * @param requestTimeoutMs Per-request timeout (virtual time under `runTest`).
         * @return A client that owns [session].
         */
        fun forTesting(
            session: WsSession,
            dispatcher: CoroutineContext = Dispatchers.Unconfined,
            requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
        ): GatewayWsClient = GatewayWsClient(session, "ws://test/ws", requestTimeoutMs, dispatcher)
    }
}

// ── Production Ktor adapter ───────────────────────────────────────────────────

/**
 * Production [WsSession] backed by a real Ktor [DefaultClientWebSocketSession].
 *
 * @param delegate The Ktor session carrying raw WS frames.
 */
class KtorWsSession internal constructor(
    private val delegate: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession,
) : WsSession {
    override suspend fun send(text: String) {
        delegate.send(io.ktor.websocket.Frame.Text(text))
    }

    override fun incoming(): Flow<String> = flow {
        for (frame in delegate.incoming) {
            if (frame is io.ktor.websocket.Frame.Text) emit(frame.readText())
        }
    }

    override suspend fun close() {
        delegate.close()
    }

    override suspend fun closeReason(): WsCloseReason? =
        delegate.closeReason.await()?.let { WsCloseReason(it.code.toInt(), it.message) }
}
