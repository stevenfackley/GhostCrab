package com.openclaw.ghostcrab.data.gateway

import com.openclaw.ghostcrab.testutil.GatewayFixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GatewayFramesTest {

    @Test fun `challenge decodes as an event carrying nonce and ts`() {
        val frame = GatewayFrameCodec.decode(GatewayFixtures.text("challenge")) as GatewayFrame.Event
        assertEquals("connect.challenge", frame.event)
        val payload = frame.payload as JsonObject
        assertEquals("71d63897-7ebd-44e8-a27a-20ac1598b846", payload.stringOrNull("nonce"))
        assertEquals("1792027507354", payload["ts"]?.jsonPrimitive?.content)
    }

    @Test fun `hello-ok decodes as an ok response with a string id`() {
        val frame = GatewayFrameCodec.decode(GatewayFixtures.text("hello-ok-paired")) as GatewayFrame.Response
        assertEquals("qa-1", frame.id)
        assertTrue(frame.ok)
        assertEquals("hello-ok", (frame.payload as JsonObject).stringOrNull("type"))
        assertNull(frame.error)
    }

    @Test fun `error response exposes the string code and details code`() {
        val frame = GatewayFrameCodec.decode(GatewayFixtures.text("err-pairing-required")) as GatewayFrame.Response
        assertFalse(frame.ok)
        assertEquals("NOT_PAIRED", frame.error?.code)
        assertEquals("PAIRING_REQUIRED", frame.error?.detailCode)
        assertEquals("af0b5d81-7bd2-4b86-80b0-4a489b541974", frame.error?.detailsObject?.stringOrNull("requestId"))
    }

    @Test fun `a failed response keeps both payload and error`() {
        val frame = GatewayFrameCodec.decode(GatewayFixtures.text("err-skill-not-found")) as GatewayFrame.Response
        assertEquals("UNAVAILABLE", frame.error?.code)
        assertTrue(frame.payload is JsonObject)
    }

    @Test fun `input that is not a protocol-4 frame decodes to null`() {
        listOf(
            "not json",
            "[1,2,3]",
            """{"jsonrpc":"2.0","id":1,"result":{}}""",
            """{"type":"res","id":7,"ok":true}""",
            """{"type":"res","id":"x"}""",
            """{"type":"event"}""",
        ).forEach { assertNull(GatewayFrameCodec.decode(it), it) }
    }

    @Test fun `request encodes type req with a string id and params`() {
        val text = GatewayFrameCodec.encode(
            RequestFrame(id = "gc-1", method = "health", params = buildJsonObject { put("probe", true) }),
        )
        val obj = Json.parseToJsonElement(text).jsonObject
        assertEquals("req", obj.stringOrNull("type"))
        assertEquals("gc-1", obj.stringOrNull("id"))
        assertEquals("health", obj.stringOrNull("method"))
        assertEquals("true", obj.getValue("params").jsonObject["probe"]?.jsonPrimitive?.content)
    }

    @Test fun `objectAt walks nested objects and stops at non-objects`() {
        val obj = Json.parseToJsonElement("""{"a":{"b":{"c":1}},"x":2}""").jsonObject
        assertEquals("1", obj.objectAt("a", "b")?.get("c")?.jsonPrimitive?.content)
        assertNull(obj.objectAt("x", "y"))
    }
}
