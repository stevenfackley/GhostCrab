package com.openclaw.ghostcrab.data.gateway

import com.openclaw.ghostcrab.data.ws.WsCloseReason
import com.openclaw.ghostcrab.data.ws.WsSession
import com.openclaw.ghostcrab.domain.exception.GatewayConnectionClosedException
import com.openclaw.ghostcrab.domain.exception.GatewayException
import com.openclaw.ghostcrab.domain.exception.GatewayNotConnectedException
import com.openclaw.ghostcrab.domain.exception.GatewayTimeoutException
import com.openclaw.ghostcrab.domain.exception.GatewayUnreachableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

/**
 * One gateway control-plane socket (ADR-0007). Request ids are `gc-1`, `gc-2`, …; each pending request is
 * parked by id until its `res` frame arrives. `connect.challenge` is captured as soon as it arrives, so a
 * caller that subscribes late still gets it. Every request is bounded by its own timeout; a caller's outer
 * timeout or cancellation propagates unchanged. When the socket ends, pending and later requests fail with a
 * typed [GatewayException] instead of hanging.
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
    private val closedCause = AtomicReference<GatewayException?>(null)

    /** Server events; `null` marks the end of the socket. Never suspends the reader: the oldest are dropped. */
    private val eventBus = MutableSharedFlow<GatewayFrame.Event?>(
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Server events (`tick`, `health`, `config.changed`, …). Best effort: a collector that falls behind loses the
     * oldest undelivered events. Completes when the socket ends (immediately for a collector that starts later).
     * Nothing is replayed, so a consumer waiting for an event its own request triggers must subscribe before
     * sending that request: `async(start = CoroutineStart.UNDISPATCHED) { withTimeoutOrNull(t) { rpc.events.first
     * { … } } }`, then `request(…)`, then await.
     */
    val events: Flow<GatewayFrame.Event> = eventBus
        .onSubscription { if (isClosed) emit(null) }
        .transformWhile { event ->
            if (event != null) emit(event)
            event != null
        }

    /** `true` once the socket has ended or [close] was called. */
    val isClosed: Boolean get() = closedCause.get() != null

    /** Requests still waiting for a response. Visible for tests. */
    internal val pendingRequestCount: Int get() = pending.size

    private val readerJob: Job = scope.launch {
        var serverClose: WsCloseReason? = null
        var transportError: Exception? = null
        try {
            session.incoming().collect { raw -> dispatch(raw) }
            serverClose = withTimeoutOrNull(CLOSE_REASON_WAIT_MS) { session.closeReason() }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // A transport error ends the socket like a close; it must not reach the uncaught handler.
            transportError = e
        } finally {
            endSession(endCauseOf(serverClose, transportError))
        }
    }

    private fun endCauseOf(serverClose: WsCloseReason?, transportError: Exception?): GatewayException =
        if (serverClose?.code == WS_CLOSE_POLICY_VIOLATION) {
            GatewayConnectionClosedException(endpoint, serverClose.code, serverClose.reason)
        } else {
            GatewayUnreachableException(endpoint, transportError)
        }

    /** Records the first end cause and fails everything still waiting with it. Safe to call more than once. */
    private fun endSession(cause: GatewayException) {
        closedCause.compareAndSet(null, cause)
        val effective = closedCause.get() ?: cause
        challenge.completeExceptionally(effective)
        pending.values.forEach { it.completeExceptionally(effective) }
        pending.clear()
        eventBus.tryEmit(null)
    }

    private fun dispatch(raw: String) {
        when (val frame = GatewayFrameCodec.decode(raw)) {
            is GatewayFrame.Response -> pending.remove(frame.id)?.complete(frame)
            is GatewayFrame.Event -> {
                if (frame.event == CONNECT_CHALLENGE_EVENT) challenge.complete(frame)
                eventBus.tryEmit(frame)
            }
            null -> Unit // Not a protocol-4 frame: ignored, like the official client does.
        }
    }

    /**
     * @param timeoutMs How long to wait for the gateway's first frame.
     * @return The `connect.challenge` event (also when it arrived before this call).
     * @throws GatewayTimeoutException if none arrives within [timeoutMs]. A caller's own timeout or
     *   cancellation propagates unchanged.
     * @throws GatewayException the end cause, if the socket ended first (see [request]).
     */
    suspend fun awaitChallenge(timeoutMs: Long = CHALLENGE_TIMEOUT_MS): GatewayFrame.Event =
        withTimeoutOrNull(timeoutMs) { challenge.await() }
            ?: throw GatewayTimeoutException("$endpoint#$CONNECT_CHALLENGE_EVENT")

    /**
     * Sends a request and suspends until its response.
     *
     * @param method RPC method, e.g. `skills.status`.
     * @param params Method parameters; `{}` by default (the gateway expects an object).
     * @param timeoutMs Per-request timeout.
     * @return The response `payload` (an empty object when the gateway sent none).
     * @throws GatewayException subtype for `ok:false` (see `toGatewayException`).
     * @throws GatewayTimeoutException if no response arrives within [timeoutMs]. A caller's own timeout or
     *   cancellation propagates unchanged, and the request is forgotten either way.
     * @throws GatewayUnreachableException if the frame could not be sent or the socket dropped.
     * @throws GatewayConnectionClosedException if the gateway closed the socket with 1008 and no response.
     * @throws GatewayNotConnectedException if [close] was called.
     */
    suspend fun request(
        method: String,
        params: JsonElement = JsonObject(emptyMap()),
        timeoutMs: Long = defaultTimeoutMs,
    ): JsonElement {
        val id = "gc-${nextId.getAndIncrement()}"
        val deferred = CompletableDeferred<GatewayFrame.Response>()
        pending[id] = deferred
        // Registered after the session ended: fail now instead of timing out.
        closedCause.get()?.let { deferred.completeExceptionally(it) }
        val answered = try {
            if (!deferred.isCompleted) send(RequestFrame(id = id, method = method, params = params))
            // OrNull: only this request's own timeout becomes a GatewayTimeoutException.
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(id)
        }
        val response = answered ?: throw GatewayTimeoutException("$endpoint#$method")
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

    /**
     * Fails every pending request and [awaitChallenge] with [GatewayNotConnectedException], completes [events],
     * stops the reader and closes the socket. Idempotent.
     *
     * @throws Exception whatever [WsSession.close] throws; the client is already closed by then.
     */
    suspend fun close() {
        endSession(GatewayNotConnectedException())
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

        /** WebSocket close code the gateway uses to refuse a socket (RFC 6455 §7.4.1). */
        private const val WS_CLOSE_POLICY_VIOLATION = 1008

        /** Upper bound for reading the close frame once the incoming stream has ended. */
        private const val CLOSE_REASON_WAIT_MS = 1_000L

        /**
         * @param session A connected socket; the client owns it from now on.
         * @param endpoint The `ws(s)://…/ws` URL, for error messages.
         * @param dispatcher Where the reader loop runs (the connection manager injects its IO dispatcher; tests
         *   a test dispatcher).
         * @param requestTimeoutMs Default per-request timeout.
         * @return A client that is already reading.
         */
        fun create(
            session: WsSession,
            endpoint: String,
            dispatcher: CoroutineContext,
            requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
        ): GatewayRpcClient = GatewayRpcClient(session, endpoint, requestTimeoutMs, dispatcher)
    }
}
