package com.openclaw.ghostcrab.data.ws

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * JSON-RPC 2.0 request envelope. `id` must be unique per session.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class JsonRpcRequest(
    @EncodeDefault val jsonrpc: String = "2.0",
    val id: Long,
    val method: String,
    val params: JsonElement? = null,
)

/**
 * JSON-RPC 2.0 response envelope. Exactly one of [result] / [error] is populated.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class JsonRpcResponse(
    @EncodeDefault val jsonrpc: String = "2.0",
    val id: Long,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
)

/**
 * JSON-RPC 2.0 server-pushed notification — no `id`, no response expected.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class JsonRpcNotification(
    @EncodeDefault val jsonrpc: String = "2.0",
    val method: String,
    val params: JsonElement? = null,
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)
