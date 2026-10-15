package com.openclaw.ghostcrab.data.ws

import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Thin abstraction over a Ktor WS session — enables injecting a fake in tests. */
interface WsSession {
    /**
     * Sends one text frame.
     *
     * @param text The frame's JSON text.
     * @throws Exception the transport failure when the frame cannot be written (e.g. the socket already closed);
     *   [com.openclaw.ghostcrab.data.gateway.GatewayRpcClient] maps it to `GatewayUnreachableException`.
     */
    suspend fun send(text: String)

    /**
     * The server's text frames, in order; other frame types are skipped. Collect it once.
     *
     * @return A flow that completes when the socket closes (then read [closeReason]).
     * @throws Exception when collected, the transport failure if the socket fails instead of closing.
     */
    fun incoming(): Flow<String>

    /**
     * Closes the socket from the client side; [incoming] then completes.
     *
     * @throws Exception the transport failure while sending the close frame.
     */
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
 * Production [WsSession] backed by a real Ktor [io.ktor.client.plugins.websocket.DefaultClientWebSocketSession].
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
