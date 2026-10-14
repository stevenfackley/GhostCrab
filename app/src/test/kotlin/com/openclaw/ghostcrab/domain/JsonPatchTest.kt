package com.openclaw.ghostcrab.domain

import com.openclaw.ghostcrab.domain.util.withLeaf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JsonPatchTest {

    @Test
    fun `withLeaf preserves unknown keys and writes values equal to model defaults`() {
        val original = Json.parseToJsonElement(
            """{"http":{"host":"127.0.0.1","port":18789,"tls":{"cert":"/etc/c.pem"}},"auth":{"mode":"oauth"}}""",
        )

        val edited = original.withLeaf(listOf("http", "host"), JsonPrimitive("0.0.0.0"))

        assertEquals(
            """{"http":{"host":"0.0.0.0","port":18789,"tls":{"cert":"/etc/c.pem"}},"auth":{"mode":"oauth"}}""",
            edited.toString(),
        )
    }

    @Test
    fun `withLeaf creates missing intermediate objects`() {
        val edited = JsonObject(emptyMap()).withLeaf(listOf("mdns", "enabled"), JsonPrimitive(false))
        assertEquals("""{"mdns":{"enabled":false}}""", edited.toString())
    }
}
