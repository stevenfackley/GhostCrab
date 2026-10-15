package com.openclaw.ghostcrab.testutil

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GatewayFixturesTest {

    @Test fun `every fixture is a res or event frame`() {
        GatewayFixtures.ALL.forEach { name ->
            val type = GatewayFixtures.frame(name)["type"]?.jsonPrimitive?.content
            assertTrue(type == "res" || type == "event", "$name has type $type")
        }
    }

    @Test fun `paired hello grants the three operator scopes`() {
        val auth = GatewayFixtures.frame("hello-ok-paired").getValue("payload").jsonObject.getValue("auth").jsonObject
        assertEquals(
            listOf("operator.read", "operator.write", "operator.admin"),
            auth.getValue("scopes").jsonArray.map { it.jsonPrimitive.content },
        )
    }
}
