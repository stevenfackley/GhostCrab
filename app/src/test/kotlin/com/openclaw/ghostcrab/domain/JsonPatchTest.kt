package com.openclaw.ghostcrab.domain

import com.openclaw.ghostcrab.domain.util.withLeaf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

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

    @Test
    fun `withLeaf rejects an empty path`() {
        assertThrows<IllegalArgumentException> {
            JsonObject(emptyMap()).withLeaf(emptyList(), JsonPrimitive(1))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "a..b", ".a"])
    fun `withLeaf rejects a dotted path with an empty segment`(dotted: String) {
        assertThrows<IllegalArgumentException> {
            JsonObject(emptyMap()).withLeaf(dotted.split('.'), JsonPrimitive(1))
        }
    }

    @Test
    fun `withLeaf replaces an array or primitive on the path with an object`() {
        val original = Json.parseToJsonElement("""{"http":[1,2],"mdns":"off","keep":true}""")

        val edited = original
            .withLeaf(listOf("http", "port"), JsonPrimitive(443))
            .withLeaf(listOf("mdns", "enabled"), JsonPrimitive(false))

        assertEquals("""{"http":{"port":443},"mdns":{"enabled":false},"keep":true}""", edited.toString())
    }
}
