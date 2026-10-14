package com.openclaw.ghostcrab.ui

import com.openclaw.ghostcrab.domain.model.SuggestedChange
import com.openclaw.ghostcrab.ui.airecommend.isSecurityAffecting
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
}
