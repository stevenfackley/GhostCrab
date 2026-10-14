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

    @Test
    fun `isSecretKey matches authorization, passphrase and pwd`() {
        listOf("authorization", "Authorization", "ssh_passphrase", "Passphrase", "pwd", "db_pwd")
            .forEach { assertTrue(isSecretKey(it), it) }
    }

    @Test
    fun `isSecretKey matches bare key and key suffixes without api`() {
        listOf("key", "KEY", "signing_key", "HMAC_KEY", "hmac-key", "signingKey", "encryptionKey")
            .forEach { assertTrue(isSecretKey(it), it) }
        listOf("monkey", "hotkey", "keys", "keepalive").forEach { assertFalse(isSecretKey(it), it) }
    }

    @Test
    fun `redacts URLs with embedded credentials regardless of key name`() {
        val config = Json.parseToJsonElement(
            """{"upstream":"https://bob:hunter2@example.com/v1","mirrors":["redis://:s3cr3t@cache:6379"],""" +
                """"docs":"https://example.com/docs"}""",
        )

        val redacted = config.redactSecrets().toString()

        assertFalse(redacted.contains("hunter2"))
        assertFalse(redacted.contains("s3cr3t"))
        assertTrue(redacted.contains("https://example.com/docs"))
        assertEquals(2, redacted.split(REDACTED_PLACEHOLDER).size - 1)
    }
}
