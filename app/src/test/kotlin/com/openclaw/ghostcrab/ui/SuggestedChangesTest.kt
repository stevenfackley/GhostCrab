package com.openclaw.ghostcrab.ui

import com.openclaw.ghostcrab.domain.model.SuggestedChange
import com.openclaw.ghostcrab.domain.util.REDACTED_PLACEHOLDER
import com.openclaw.ghostcrab.ui.airecommend.displayValueFor
import com.openclaw.ghostcrab.ui.airecommend.isApplicable
import com.openclaw.ghostcrab.ui.airecommend.isSecurityAffecting
import com.openclaw.ghostcrab.ui.airecommend.sectionPatches
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SuggestedChangesTest {

    private fun change(section: String, key: String, value: String = "1") =
        SuggestedChange(section = section, key = key, currentValue = null, suggestedValue = value)

    @Test
    fun `auth, credential and exposure paths are security affecting`() {
        assertTrue(change("gateway", "auth.mode").isSecurityAffecting)
        assertTrue(change("providers", "openai.api_key").isSecurityAffecting)
        assertTrue(change("gateway", "http.host").isSecurityAffecting)
        assertTrue(change("gateway", "tls.cert").isSecurityAffecting)
    }

    @Test
    fun `ordinary tuning paths are not security affecting`() {
        assertFalse(change("gateway", "http.port").isSecurityAffecting)
        assertFalse(change("models", "timeout_ms").isSecurityAffecting)
        assertFalse(change("gateway", "max_connections").isSecurityAffecting)
    }

    @Test
    fun `keys inside an object value are checked against the security predicate`() {
        assertTrue(change("gateway", "http", """{"host":"0.0.0.0"}""").isSecurityAffecting)
        assertTrue(change("gateway", "http", """{"limits":{"cors":["*"]}}""").isSecurityAffecting)
        assertFalse(change("gateway", "http", """{"port":18789}""").isSecurityAffecting)
    }

    @Test
    fun `a null value deletes config and is always security affecting`() {
        assertTrue(change("gateway", "http", "null").isSecurityAffecting)
        assertTrue(change("models", "default", "null").isSecurityAffecting)
        assertTrue(change("models", "default", " null ").isSecurityAffecting)
        assertTrue(change("models", "limits", """{"timeout_ms":null}""").isSecurityAffecting)
        assertFalse(change("models", "default", "\"null\"").isSecurityAffecting)
    }

    @Test
    fun `suggestions echoing the redaction placeholder are not applicable`() {
        assertFalse(change("gateway", "auth.token", "[REDACTED]").isApplicable)
        assertFalse(change("gateway", "auth.token", "\"[REDACTED]\"").isApplicable)
        assertFalse(change("gateway", "auth", """{"token":"[REDACTED]","x":1}""").isApplicable)
        assertFalse(change("gateway", "auth", """{"token":"[REDACTED]"}""").isApplicable)
        assertTrue(change("models", "timeout_ms", "60000").isApplicable)
    }

    @Test
    fun `displayValueFor masks the whole value when any path segment is a secret key`() {
        assertEquals(REDACTED_PLACEHOLDER, displayValueFor("gateway.auth.token", "\"abc\""))
        assertEquals(REDACTED_PLACEHOLDER, displayValueFor("providers.openai.api_key", "sk-live"))
        assertEquals(REDACTED_PLACEHOLDER, displayValueFor("gateway.token.value", "\"abc\""))
    }

    @Test
    fun `displayValueFor redacts secrets inside object values`() {
        val shown = displayValueFor("gateway.auth", """{"mode":"bearer","token":"s3cr3t"}""")

        assertFalse(shown.contains("s3cr3t"))
        assertTrue(shown.contains("bearer"))
        assertTrue(shown.contains(REDACTED_PLACEHOLDER))
    }

    @Test
    fun `displayValueFor redacts a bare credential URL`() {
        assertEquals(REDACTED_PLACEHOLDER, displayValueFor("gateway.upstream", "https://bob:hunter2@example.com"))
    }

    @Test
    fun `displayValueFor leaves ordinary values exactly as suggested`() {
        assertEquals("60000", displayValueFor("models.timeout_ms", "60000"))
        assertEquals("\"gpt-4o\"", displayValueFor("models.default", "\"gpt-4o\""))
        assertEquals("gpt-4o", displayValueFor("models.default", "gpt-4o"))
    }

    @Test
    fun `changes in one section merge into a single nested patch`() {
        val patches = sectionPatches(
            listOf(
                change("gateway", "http.port", "443"),
                change("gateway", "http.host", "\"127.0.0.1\""),
                change("models", "timeout_ms", "60000"),
            ),
        )

        assertEquals(listOf("gateway", "models"), patches.keys.toList())
        assertEquals("""{"http":{"port":443,"host":"127.0.0.1"}}""", patches.getValue("gateway").toString())
        assertEquals("""{"timeout_ms":60000}""", patches.getValue("models").toString())
    }

    @Test
    fun `bare-word suggested values are sent as JSON strings`() {
        val patch = sectionPatches(listOf(change("models", "default", "gpt-4o"))).getValue("models")
        assertEquals("""{"default":"gpt-4o"}""", patch.toString())
    }
}
