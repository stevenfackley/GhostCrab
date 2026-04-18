package com.openclaw.ghostcrab.data.ws

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JsonRpcTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `request round-trips`() {
        val req = JsonRpcRequest(
            id = 7,
            method = "skills.install",
            params = buildJsonObject { put("slug", JsonPrimitive("foo/bar")) },
        )
        val wire = json.encodeToString(JsonRpcRequest.serializer(), req)
        assertTrue(wire.contains("\"jsonrpc\":\"2.0\""))
        val decoded = json.decodeFromString(JsonRpcRequest.serializer(), wire)
        assertEquals(7L, decoded.id)
        assertEquals("skills.install", decoded.method)
    }

    @Test
    fun `response with result parses`() {
        val wire = """{"jsonrpc":"2.0","id":42,"result":{"ok":true}}"""
        val res = json.decodeFromString(JsonRpcResponse.serializer(), wire)
        assertEquals(42L, res.id)
        assertNull(res.error)
        assertEquals(JsonPrimitive(true), (res.result as JsonObject)["ok"])
    }

    @Test
    fun `response with error parses`() {
        val wire = """{"jsonrpc":"2.0","id":5,"error":{"code":-32003,"message":"missing scope"}}"""
        val res = json.decodeFromString(JsonRpcResponse.serializer(), wire)
        assertEquals(-32003, res.error?.code)
        assertEquals("missing scope", res.error?.message)
    }

    @Test
    fun `notification has no id`() {
        val wire = """{"jsonrpc":"2.0","method":"skills.install.progress","params":{"phase":"downloading"}}"""
        val n = json.decodeFromString(JsonRpcNotification.serializer(), wire)
        assertEquals("skills.install.progress", n.method)
    }
}
