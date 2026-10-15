package com.openclaw.ghostcrab.ui

import com.openclaw.ghostcrab.domain.model.config.toGatewaySection
import com.openclaw.ghostcrab.ui.config.sections.GatewayField
import com.openclaw.ghostcrab.ui.config.sections.gatewayFieldsFor
import com.openclaw.ghostcrab.ui.config.sections.inlineErrorFieldsOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Ties [inlineErrorFieldsOf] (which the config editor uses to decide whether a validation error is visible) to the
 * fields `GatewaySectionEditor` composes: both read [gatewayFieldsFor], and the editor renders an error under a field
 * only when [GatewayField.showsError] is set.
 */
class GatewayEditorFieldsTest {

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private val variants = listOf(
        """{"port":1,"auth":{"mode":"token"}}""",
        """{"port":1,"auth":{"mode":"password"}}""",
        """{"bind":"lan","auth":{"mode":"none"}}""",
        """{"auth":{"mode":"trusted-proxy"}}""",
        """{"mode":"local"}""",
        """{}""",
    )

    @Test fun `inline error paths are exactly the composed fields that render an error`() {
        variants.forEach { text ->
            val value = json(text)
            val composed = gatewayFieldsFor(value.toGatewaySection())
            assertEquals(
                composed.filter { it.showsError }.map { it.path }.toSet(),
                inlineErrorFieldsOf("gateway", value),
                text,
            )
        }
    }

    @Test fun `gateway mode is composed read-only and never claims an inline error`() {
        val value = json("""{"mode":"local","auth":{"mode":"token"}}""")
        assertTrue(GatewayField.Mode in gatewayFieldsFor(value.toGatewaySection()))
        assertFalse(GatewayField.Mode.showsError)
        assertFalse("gateway.mode" in inlineErrorFieldsOf("gateway", value))
    }

    @Test fun `the token field is composed only for token mode or an unset mode`() {
        val expected = listOf("token" to true, "password" to false, "none" to false, "trusted-proxy" to false)
        expected.forEach { (mode, shown) ->
            val section = json("""{"auth":{"mode":"$mode"}}""").toGatewaySection()
            assertEquals(shown, GatewayField.Token in gatewayFieldsFor(section), mode)
        }
        assertTrue(GatewayField.Token in gatewayFieldsFor(json("{}").toGatewaySection()))
    }

    @Test fun `field paths are the gateway's config paths, in screen order`() {
        assertEquals(
            listOf("gateway.mode", "gateway.port", "gateway.bind", "gateway.auth.mode", "gateway.auth.token"),
            GatewayField.entries.map { it.path },
        )
    }

    @Test fun `raw sections and untyped gateway sections claim no inline field`() {
        assertTrue(inlineErrorFieldsOf("agents", json("""{"port":1}""")).isEmpty())
        assertTrue(inlineErrorFieldsOf("gateway", json("""{"auth":{"mode":"oauth"}}""")).isEmpty())
    }
}
