package com.openclaw.ghostcrab.domain

import com.openclaw.ghostcrab.domain.util.REDACTED_PLACEHOLDER
import com.openclaw.ghostcrab.domain.util.isSecretKey
import com.openclaw.ghostcrab.domain.util.redactSecrets
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfigSensitivityTest {

    @Test
    fun `redacts nested tokens and api keys but keeps everything else`() {
        val config = Json.parseToJsonElement(
            """{"gateway":{"http":{"port":18789},"auth":{"mode":"bearer","token":"s3cr3t"}},""" +
                """"providers":[{"name":"openai","api_key":"sk-live"},{"name":"local","apiKey":"x"}]}""",
        )

        val redacted = config.redactSecrets().toString()

        assertFalse(redacted.contains("s3cr3t"))
        assertFalse(redacted.contains("sk-live"))
        assertTrue(redacted.contains("18789"))
        assertTrue(redacted.contains("\"mode\":\"bearer\""))
        assertEquals(3, redacted.split(REDACTED_PLACEHOLDER).size - 1)
    }

    @Test
    fun `isSecretKey matches common secret spellings case-insensitively`() {
        listOf("token", "Bearer_Token", "client_secret", "PASSWORD", "api-key", "apiKey", "private_key")
            .forEach { assertTrue(isSecretKey(it), it) }
        listOf("port", "mode", "host", "serviceName").forEach { assertFalse(isSecretKey(it), it) }
    }
}
