package com.openclaw.ghostcrab.data.gateway

import com.openclaw.ghostcrab.data.ws.WsSession
import com.openclaw.ghostcrab.domain.exception.GatewayTimeoutException
import com.openclaw.ghostcrab.domain.exception.GatewayUnreachableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

/**
 * One gateway control-plane socket (ADR-0007). Request ids are `gc-1`, `gc-2`, …; each pending request is
 * parked by id until its `res` frame arrives. `connect.challenge` is captured as soon as it arrives, so a
 * caller that subscribes late still gets it. Every request is bounded by a timeout; when the socket ends,
 * pending and later requests fail with [GatewayUnreachableException] instead of hanging.
 *
 * Construct with [create]; call [close] when the session ends.
 */
class GatewayRpcClient private constructor(
    private val session: WsSession,
    /** The `ws(s)://…/ws` endpoint, used in error messages. */
    val endpoint: String,
    private val defaultTimeoutMs: Long,
    dispatcher: CoroutineContext,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<GatewayFrame.Response>>()
    private val challenge = CompletableDeferred<GatewayFrame.Event>()
    private val _events = MutableSharedFlow<GatewayFrame.Event>(extraBufferCapacity = EVENT_BUFFER)

    @Volatile private var closedCause: GatewayUnreachableException? = null

    /** Server events (`tick`, `health`, `skills.changed`, …). Best effort: a slow collector drops events. */
    val events: SharedFlow<GatewayFrame.Event> = _events.asSharedFlow()

    /** `true` once the socket has ended. */
    val isClosed: Boolean get() = closedCause != null

    private val readerJob: Job = scope.launch {
        var transportError: Exception? = null
        try {
            session.incoming().collect { raw -> dispatch(raw) }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // A transport error ends the socket like a close; it must not reach the uncaught handler.
            transportError = e
        } finally {
            val cause = GatewayUnreachableException(endpoint, transportError)
            closedCause = cause
            challenge.completeExceptionally(cause)
            pending.values.forEach { it.completeExceptionally(cause) }
            pending.clear()
        }
    }

    private fun dispatch(raw: String) {
        when (val frame = GatewayFrameCodec.decode(raw)) {
            is GatewayFrame.Response -> pending.remove(frame.id)?.complete(frame)
            is GatewayFrame.Event -> {
                if (frame.event == CONNECT_CHALLENGE_EVENT) challenge.complete(frame)
                _events.tryEmit(frame)
            }
            null -> Unit // Not a protocol-4 frame: ignored, like the official client does.
        }
    }

    /**
     * @param timeoutMs How long to wait for the gateway's first frame.
     * @return The `connect.challenge` event (also when it arrived before this call).
     * @throws GatewayTimeoutException if none arrives in time.
     * @throws GatewayUnreachableException if the socket ended first.
     */
    suspend fun awaitChallenge(timeoutMs: Long = CHALLENGE_TIMEOUT_MS): GatewayFrame.Event = try {
        withTimeout(timeoutMs) { challenge.await() }
    } catch (e: TimeoutCancellationException) {
        throw GatewayTimeoutException("$endpoint#$CONNECT_CHALLENGE_EVENT", e)
    }

    /**
     * Sends a request and suspends until its response.
     *
     * @param method RPC method, e.g. `skills.status`.
     * @param params Method parameters; `{}` by default (the gateway expects an object).
     * @param timeoutMs Per-request timeout.
     * @return The response `payload` (an empty object when the gateway sent none).
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayException subtype for `ok:false`
     *   (see `toGatewayException`).
     * @throws GatewayTimeoutException if no response arrives in time.
     * @throws GatewayUnreachableException if the frame could not be sent or the socket ended.
     */
    suspend fun request(
        method: String,
        params: JsonElement = JsonObject(emptyMap()),
        timeoutMs: Long = defaultTimeoutMs,
    ): JsonElement {
        val id = "gc-${nextId.getAndIncrement()}"
        val deferred = CompletableDeferred<GatewayFrame.Response>()
        pending[id] = deferred
        // Registered after the reader failed the parked requests: fail now instead of timing out.
        closedCause?.let { deferred.completeExceptionally(it) }
        val response = try {
            if (!deferred.isCompleted) send(RequestFrame(id = id, method = method, params = params))
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            throw GatewayTimeoutException("$endpoint#$method", e)
        } finally {
            pending.remove(id)
        }
        if (!response.ok) {
            val error = response.error ?: ErrorShape(code = "UNKNOWN", message = "ok:false without an error object")
            throw error.toGatewayException(endpoint, method)
        }
        return response.payload ?: JsonObject(emptyMap())
    }

    private suspend fun send(frame: RequestFrame) {
        try {
            session.send(GatewayFrameCodec.encode(frame))
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            throw GatewayUnreachableException(endpoint, e)
        }
    }

    /** Stops the reader and closes the socket. Idempotent. */
    suspend fun close() {
        readerJob.cancel()
        scope.cancel()
        session.close()
    }

    companion object {
        /** First frame of every gateway socket. */
        const val CONNECT_CHALLENGE_EVENT = "connect.challenge"

        /** Default per-request timeout. */
        const val DEFAULT_REQUEST_TIMEOUT_MS = 30_000L

        /** How long to wait for `connect.challenge` (the real gateway sends it within ~2 ms). */
        const val CHALLENGE_TIMEOUT_MS = 10_000L

        private const val EVENT_BUFFER = 64

        /**
         * @param session A connected socket; the client owns it from now on.
         * @param endpoint The `ws(s)://…/ws` URL, for error messages.
         * @param dispatcher Where the reader loop runs (the connection manager passes its IO dispatcher).
         * @param requestTimeoutMs Default per-request timeout.
         * @return A client that is already reading.
         */
        fun create(
            session: WsSession,
            endpoint: String,
            dispatcher: CoroutineContext = Dispatchers.Default,
            requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
        ): GatewayRpcClient = GatewayRpcClient(session, endpoint, requestTimeoutMs, dispatcher)
    }
}
