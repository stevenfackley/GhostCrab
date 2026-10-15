package com.openclaw.ghostcrab.data

import com.openclaw.ghostcrab.data.gateway.GatewayRpcClient
import com.openclaw.ghostcrab.data.gateway.stringOrNull
import com.openclaw.ghostcrab.data.impl.ConfigRepositoryImpl
import com.openclaw.ghostcrab.domain.exception.GatewayApiException
import com.openclaw.ghostcrab.domain.exception.GatewayMissingScopeException
import com.openclaw.ghostcrab.domain.exception.GatewayNotConnectedException
import com.openclaw.ghostcrab.domain.exception.GatewayRpcException
import com.openclaw.ghostcrab.testutil.GatewayFixtures
import com.openclaw.ghostcrab.testutil.ScriptedWsSession
import com.openclaw.ghostcrab.testutil.ScriptedWsSession.Companion.fixtures
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Replays the 2026.10.3 `config.get` / `config.patch` frames (ADR-0007). The REST-era cases (ETag / `If-Match`
 * through a mocked client, a null `If-Match` before any read) are gone with that transport; their still-valid
 * intent (hash caching, write-returned hash, per-session binding, read/write serialisation) is kept below.
 */
class ConfigRepositoryImplTest {

    private val fixtureHash = "hmac-sha256:v1:u_qU7JLKF7G9u0jc_GfUp-dKovJYwiLpy0gNxuKjka0"

    private val okPatch: JsonObject = GatewayFixtures.frame("res-config-patch")
    private val patchedHash = "hmac-sha256:v1:9zRz_TNpxroYBBCZhMFq3HGpv1dFAcFxPnmwn7VTXYE"

    private fun configSession(patchReply: JsonObject?) = ScriptedWsSession(
        respond = { method, _ ->
            when (method) {
                "config.get" -> GatewayFixtures.frame("res-config-get")
                "config.patch" -> patchReply
                else -> null
            }
        },
    )

    private fun TestScope.rpc(session: ScriptedWsSession) =
        GatewayRpcClient.create(session, "ws://gw/ws", UnconfinedTestDispatcher(testScheduler))

    private fun ScriptedWsSession.methods() = sent.map { it.stringOrNull("method") }

    private fun ScriptedWsSession.patchHashes() =
        sent.filter { it.stringOrNull("method") == "config.patch" }
            .map { (it["params"] as JsonObject).stringOrNull("baseHash") }

    @Test fun `getConfig maps parsed sections and the hash`() = runTest {
        val socket = rpc(configSession(okPatch))
        val config = ConfigRepositoryImpl { socket }.getConfig()
        assertEquals(setOf("gateway", "agents"), config.sections.keys)
        assertEquals("18789", config.sections.getValue("gateway").jsonObject["port"].toString())
        assertEquals(fixtureHash, config.etag)
    }

    @Test fun `getConfig when not connected throws GatewayNotConnectedException`() = runTest {
        assertThrows<GatewayNotConnectedException> {
            ConfigRepositoryImpl { throw GatewayNotConnectedException() }.getConfig()
        }
    }

    @Test fun `updateConfig patches one section against the cached hash`() = runTest {
        val session = configSession(okPatch)
        val socket = rpc(session)
        val repo = ConfigRepositoryImpl { socket }
        repo.getConfig()
        repo.updateConfig("gateway", buildJsonObject { put("port", 18790) })
        val params = session.paramsOf("config.patch")
        assertEquals("""{"gateway":{"port":18790}}""", params.stringOrNull("raw"))
        assertEquals(fixtureHash, params.stringOrNull("baseHash"))
    }

    @Test fun `updateConfig without a prior read fetches the hash first`() = runTest {
        val session = configSession(okPatch)
        val socket = rpc(session)
        ConfigRepositoryImpl { socket }.updateConfig("gateway", buildJsonObject { put("port", 18790) })
        assertEquals(listOf("config.get", "config.patch"), session.methods())
        assertEquals(fixtureHash, session.paramsOf("config.patch").stringOrNull("baseHash"))
    }

    @Test fun `the hash a patch returns is the next baseHash`() = runTest {
        val session = configSession(okPatch)
        val socket = rpc(session)
        val repo = ConfigRepositoryImpl { socket }
        repo.updateConfig("gateway", buildJsonObject { put("port", 18790) })
        repo.updateConfig("gateway", buildJsonObject { put("port", 18791) })
        assertEquals(listOf("config.get", "config.patch", "config.patch"), session.methods())
        assertEquals(listOf(fixtureHash, patchedHash), session.patchHashes())
    }

    @Test fun `a patch answer without a hash makes the next write re-read`() = runTest {
        val noHash = buildJsonObject {
            put("type", "res")
            put("ok", true)
            putJsonObject("payload") { put("ok", true) }
        }
        val session = configSession(noHash)
        val socket = rpc(session)
        val repo = ConfigRepositoryImpl { socket }
        repo.getConfig()
        repo.updateConfig("gateway", buildJsonObject { put("port", 18790) })
        repo.updateConfig("gateway", buildJsonObject { put("port", 18791) })
        assertEquals(listOf("config.get", "config.patch", "config.get", "config.patch"), session.methods())
        assertEquals(listOf(fixtureHash, fixtureHash), session.patchHashes())
    }

    @Test fun `a stale hash is HTTP-412 style so the editor reloads`() = runTest {
        val socket = rpc(configSession(GatewayFixtures.frame("err-config-stale-hash")))
        val repo = ConfigRepositoryImpl { socket }
        val e = assertThrows<GatewayApiException> { repo.updateConfig("gateway", JsonObject(emptyMap())) }
        assertEquals(412, e.statusCode)
        assertEquals("config changed since last load; re-run config.get and retry", e.body)
    }

    @Test fun `a missing hash is HTTP-412 style too`() = runTest {
        val socket = rpc(configSession(GatewayFixtures.frame("err-config-base-hash-required")))
        val repo = ConfigRepositoryImpl { socket }
        val e = assertThrows<GatewayApiException> { repo.updateConfig("gateway", JsonObject(emptyMap())) }
        assertEquals(412, e.statusCode)
    }

    @Test fun `a validation rejection is HTTP-422 style with the server text`() = runTest {
        val socket = rpc(configSession(GatewayFixtures.frame("err-config-invalid")))
        val repo = ConfigRepositoryImpl { socket }
        val patch = buildJsonObject { put("port", 99999) }
        val e = assertThrows<GatewayApiException> { repo.updateConfig("gateway", patch) }
        assertEquals(422, e.statusCode)
        assertTrue(e.body.orEmpty().startsWith("invalid config: gateway.port"))
    }

    @Test fun `a write error other than INVALID_REQUEST passes through typed`() = runTest {
        val unavailable = buildJsonObject {
            put("type", "res")
            put("ok", false)
            putJsonObject("error") {
                put("code", "UNAVAILABLE")
                put("message", "config write rate limit exceeded")
            }
        }
        val socket = rpc(configSession(unavailable))
        val e = assertThrows<GatewayRpcException> {
            ConfigRepositoryImpl { socket }.updateConfig("gateway", JsonObject(emptyMap()))
        }
        assertEquals("UNAVAILABLE", e.code)
    }

    @Test fun `a missing admin scope passes through typed`() = runTest {
        val socket = rpc(ScriptedWsSession(respond = fixtures("config.get" to "err-missing-scope")))
        assertThrows<GatewayMissingScopeException> { ConfigRepositoryImpl { socket }.getConfig() }
    }

    @Test fun `a hash from an earlier session is never reused`() = runTest {
        val first = configSession(okPatch)
        val second = configSession(okPatch)
        var current = rpc(first)
        val repo = ConfigRepositoryImpl { current }
        repo.getConfig()
        current = rpc(second)
        repo.updateConfig("gateway", JsonObject(emptyMap()))
        assertEquals(listOf("config.get", "config.patch"), second.methods())
        assertNull(first.sent.firstOrNull { it.stringOrNull("method") == "config.patch" })
    }

    @Test fun `a read that overlaps a write cannot overwrite the write's newer hash`() = runTest {
        var holdReads = true
        val session = ScriptedWsSession(
            respond = { method, _ ->
                when (method) {
                    "config.get" -> if (holdReads) null else GatewayFixtures.frame("res-config-get")
                    "config.patch" -> okPatch
                    else -> null
                }
            },
        )
        val socket = rpc(session)
        val repo = ConfigRepositoryImpl { socket }

        val read = launch { repo.getConfig() }
        runCurrent()
        val write = launch { repo.updateConfig("gateway", JsonObject(emptyMap())) }
        runCurrent()
        holdReads = false
        val readId = session.sent.first { it.stringOrNull("method") == "config.get" }.stringOrNull("id")!!
        session.reply(readId, GatewayFixtures.frame("res-config-get"))
        joinAll(read, write)
        repo.updateConfig("gateway", JsonObject(emptyMap()))

        assertEquals(listOf("config.get", "config.patch", "config.patch"), session.methods())
        assertEquals(listOf(fixtureHash, patchedHash), session.patchHashes())
    }
}
