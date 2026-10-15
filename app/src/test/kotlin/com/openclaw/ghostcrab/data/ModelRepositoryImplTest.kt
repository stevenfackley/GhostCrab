package com.openclaw.ghostcrab.data

import com.openclaw.ghostcrab.data.gateway.GatewayRpcClient
import com.openclaw.ghostcrab.data.gateway.stringOrNull
import com.openclaw.ghostcrab.data.impl.ModelRepositoryImpl
import com.openclaw.ghostcrab.data.impl.modelRef
import com.openclaw.ghostcrab.domain.exception.GatewayApiException
import com.openclaw.ghostcrab.domain.exception.GatewayMissingScopeException
import com.openclaw.ghostcrab.domain.exception.GatewayNotConnectedException
import com.openclaw.ghostcrab.testutil.GatewayFixtures
import com.openclaw.ghostcrab.testutil.ScriptedWsSession
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * `models.list` + `agents.defaults.model.primary` over the session socket (ADR-0007). The REST-era cases (DTO
 * mapping through a mocked client, `POST /api/models/active`) are gone with that transport; their still-valid
 * intent (mapping, display-name fallback, nothing active by default, empty list, typed errors, not connected) is
 * kept below.
 */
class ModelRepositoryImplTest {

    /** Schema-shaped `models.list` answer (ModelsListResult, protocol 2026.10.3). */
    private val modelsReply: JsonObject = Json.parseToJsonElement(
        """
        {"type":"res","ok":true,"payload":{"models":[
          {"id":"claude-sonnet-4-6","name":"Claude Sonnet 4.6","provider":"anthropic","available":true,
           "input":["text","image"],"reasoning":true},
          {"id":"gpt-6-astra","name":"GPT-6 Astra","provider":"openai","available":false,
           "unavailableReason":"missing-auth","input":["text"]}
        ],"accountSelection":{"kind":"automatic","label":"Automatic account selection"}}}
        """.trimIndent(),
    ).jsonObject

    private fun configWithPrimary(primary: String): JsonObject = Json.parseToJsonElement(
        """{"type":"res","ok":true,"payload":{"parsed":{"agents":{"defaults":{"model":{"primary":"$primary"}}}},""" +
            """"hash":"h-1"}}""",
    ).jsonObject

    private val okPatch: JsonObject =
        Json.parseToJsonElement("""{"type":"res","ok":true,"payload":{"ok":true}}""").jsonObject

    private fun TestScope.repo(
        respond: (String, JsonObject) -> JsonObject?,
    ): Pair<ModelRepositoryImpl, ScriptedWsSession> {
        val session = ScriptedWsSession(respond = respond)
        val rpc = GatewayRpcClient.create(session, "ws://gw/ws", UnconfinedTestDispatcher(testScheduler))
        return ModelRepositoryImpl { rpc } to session
    }

    // ── getModels ─────────────────────────────────────────────────────────────

    @Test fun `models map to provider-qualified refs and the configured primary is active`() = runTest {
        val (models, _) = repo { method, _ ->
            when (method) {
                "models.list" -> modelsReply
                "config.get" -> configWithPrimary("anthropic/claude-sonnet-4-6")
                else -> null
            }
        }
        val list = models.getModels()
        assertEquals(listOf("anthropic/claude-sonnet-4-6", "openai/gpt-6-astra"), list.map { it.id })
        assertEquals("anthropic", list[0].provider)
        assertEquals("Claude Sonnet 4.6", list[0].displayName)
        assertTrue(list[0].isActive)
        assertEquals("ready", list[0].status)
        assertEquals(listOf("text", "image", "reasoning"), list[0].capabilities)
        assertFalse(list[1].isActive)
        assertEquals("missing-auth", list[1].status)
    }

    @Test fun `the string form of agents defaults model also marks the active entry`() = runTest {
        val stringForm = Json.parseToJsonElement(
            """{"type":"res","ok":true,"payload":{"parsed":{"agents":{"defaults":{"model":"openai/gpt-6-astra"}}}}}""",
        ).jsonObject
        val (models, _) = repo { method, _ ->
            when (method) {
                "models.list" -> modelsReply
                "config.get" -> stringForm
                else -> null
            }
        }
        assertEquals(listOf(false, true), models.getModels().map { it.isActive })
    }

    @Test fun `a bare model id marks an entry active only when no other provider has that id`() = runTest {
        val catalogue = Json.parseToJsonElement(
            """
            {"type":"res","ok":true,"payload":{"models":[
              {"id":"shared","provider":"anthropic"},
              {"id":"shared","provider":"openrouter"},
              {"id":"solo","provider":"openai"}
            ]}}
            """.trimIndent(),
        ).jsonObject
        listOf(
            "shared" to listOf(false, false, false),
            "solo" to listOf(false, false, true),
            "openrouter/shared" to listOf(false, true, false),
        ).forEach { (primary, expected) ->
            val (models, _) = repo { method, _ ->
                when (method) {
                    "models.list" -> catalogue
                    "config.get" -> configWithPrimary(primary)
                    else -> null
                }
            }
            assertEquals(expected, models.getModels().map { it.isActive }, primary)
        }
    }

    @Test fun `a model without a name uses its id as the display name`() = runTest {
        val unnamed = Json.parseToJsonElement(
            """{"type":"res","ok":true,"payload":{"models":[{"id":"llama3","provider":"ollama"}]}}""",
        ).jsonObject
        val (models, _) = repo { method, _ ->
            when (method) {
                "models.list" -> unnamed
                "config.get" -> GatewayFixtures.frame("res-config-get")
                else -> null
            }
        }
        val model = models.getModels().single()
        assertEquals("ollama/llama3", model.id)
        assertEquals("llama3", model.displayName)
        assertEquals("ready", model.status)
    }

    @Test fun `nothing is active while no primary model is configured`() = runTest {
        val (models, _) = repo { method, _ ->
            when (method) {
                "models.list" -> modelsReply
                "config.get" -> GatewayFixtures.frame("res-config-get")
                else -> null
            }
        }
        assertTrue(models.getModels().none { it.isActive })
    }

    @Test fun `the real empty catalogue maps to an empty list`() = runTest {
        val (models, _) = repo { method, _ ->
            when (method) {
                "models.list" -> GatewayFixtures.frame("res-models-list")
                "config.get" -> GatewayFixtures.frame("res-config-get")
                else -> null
            }
        }
        assertTrue(models.getModels().isEmpty())
    }

    @Test fun `without operator admin the list still loads with nothing active`() = runTest {
        val (models, _) = repo { method, _ ->
            when (method) {
                "models.list" -> modelsReply
                "config.get" -> GatewayFixtures.frame("err-missing-scope")
                else -> null
            }
        }
        val list = models.getModels()
        assertEquals(2, list.size)
        assertTrue(list.none { it.isActive })
    }

    @Test fun `a refused models list passes through typed`() = runTest {
        val (models, _) = repo { method, _ ->
            if (method == "models.list") GatewayFixtures.frame("err-missing-scope") else null
        }
        assertThrows<GatewayMissingScopeException> { models.getModels() }
    }

    @Test fun `disconnected is GatewayNotConnectedException`() = runTest {
        val disconnected = ModelRepositoryImpl { throw GatewayNotConnectedException() }
        assertThrows<GatewayNotConnectedException> { disconnected.getModels() }
    }

    // ── setActiveModel ────────────────────────────────────────────────────────

    @Test fun `setActiveModel patches agents defaults model primary against the current hash`() = runTest {
        val (models, session) = repo { method, _ ->
            when (method) {
                "config.get" -> configWithPrimary("anthropic/claude-sonnet-4-6")
                "config.patch" -> okPatch
                else -> null
            }
        }
        models.setActiveModel("openai/gpt-6-astra")
        val params = session.paramsOf("config.patch")
        assertEquals(
            """{"agents":{"defaults":{"model":{"primary":"openai/gpt-6-astra"}}}}""",
            params.stringOrNull("raw"),
        )
        assertEquals("h-1", params.stringOrNull("baseHash"))
        assertFalse(session.sent.any { it.stringOrNull("method") == "models.set" })
    }

    @Test fun `a rejected model is HTTP-422 style`() = runTest {
        val rejection = Json.parseToJsonElement(
            """{"type":"res","ok":false,""" +
                """"error":{"code":"INVALID_REQUEST","message":"invalid config: unknown model x/y"}}""",
        ).jsonObject
        val (models, _) = repo { method, _ ->
            when (method) {
                "config.get" -> configWithPrimary("a/b")
                "config.patch" -> rejection
                else -> null
            }
        }
        assertEquals(422, assertThrows<GatewayApiException> { models.setActiveModel("x/y") }.statusCode)
    }

    @Test fun `a schema issue on the primary model is HTTP-422 style, never a field error`() = runTest {
        val rejection = Json.parseToJsonElement(
            """
            {"type":"res","ok":false,"error":{"code":"INVALID_REQUEST",
             "message":"invalid config: agents.defaults.model.primary: Invalid model ref",
             "details":{"issues":[{"path":"agents.defaults.model.primary","message":"Invalid model ref"}]}}}
            """.trimIndent(),
        ).jsonObject
        val (models, _) = repo { method, _ ->
            when (method) {
                "config.get" -> configWithPrimary("a/b")
                "config.patch" -> rejection
                else -> null
            }
        }
        val e = assertThrows<GatewayApiException> { models.setActiveModel("x") }
        assertEquals(422, e.statusCode)
        assertEquals("invalid config: agents.defaults.model.primary: Invalid model ref", e.body)
    }

    @Test fun `a concurrent config change is the same HTTP-412 style error the editor reloads on`() = runTest {
        val (models, _) = repo { method, _ ->
            when (method) {
                "config.get" -> configWithPrimary("a/b")
                "config.patch" -> GatewayFixtures.frame("err-config-stale-hash")
                else -> null
            }
        }
        val e = assertThrows<GatewayApiException> { models.setActiveModel("x/y") }
        assertEquals(412, e.statusCode)
        assertEquals("config changed since last load; re-run config.get and retry", e.body)
    }

    @Test fun `setActiveModel while disconnected is GatewayNotConnectedException`() = runTest {
        val disconnected = ModelRepositoryImpl { throw GatewayNotConnectedException() }
        assertThrows<GatewayNotConnectedException> { disconnected.setActiveModel("a/b") }
    }

    @Test fun `refs are provider-qualified exactly once`() {
        assertEquals("anthropic/claude", modelRef("anthropic", "claude"))
        assertEquals("openrouter/x", modelRef("openrouter", "openrouter/x"))
    }
}
