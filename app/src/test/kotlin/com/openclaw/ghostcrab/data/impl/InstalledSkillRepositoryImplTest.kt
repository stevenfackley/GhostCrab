package com.openclaw.ghostcrab.data.impl

import app.cash.turbine.test
import com.openclaw.ghostcrab.data.gateway.GatewayRpcClient
import com.openclaw.ghostcrab.data.gateway.stringOrNull
import com.openclaw.ghostcrab.domain.exception.GatewayApiException
import com.openclaw.ghostcrab.domain.exception.GatewayMethodUnsupportedException
import com.openclaw.ghostcrab.domain.exception.GatewayMissingScopeException
import com.openclaw.ghostcrab.domain.exception.GatewayNotConnectedException
import com.openclaw.ghostcrab.domain.exception.GatewayProtocolException
import com.openclaw.ghostcrab.domain.exception.GatewayTimeoutException
import com.openclaw.ghostcrab.domain.exception.GatewayUnreachableException
import com.openclaw.ghostcrab.domain.model.SkillInstallError
import com.openclaw.ghostcrab.domain.model.SkillInstallProgress
import com.openclaw.ghostcrab.domain.model.SkillSource
import com.openclaw.ghostcrab.testutil.GatewayFixtures
import com.openclaw.ghostcrab.testutil.ScriptedWsSession
import com.openclaw.ghostcrab.testutil.ScriptedWsSession.Companion.fixtures
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Replays the 2026.10.3 frames (ADR-0007): `skills.status` lists, `skills.install` answers once (no progress
 * events), `skills.uninstall` does not exist. The JSON-RPC-era cases (progress notifications, `-32003`, a
 * `skills.uninstall` error, a malformed install result failing a successful install) are gone with that protocol;
 * their still-valid intent (typed errors, strict list shapes, timeouts, cancellation) is kept below.
 */
class InstalledSkillRepositoryImplTest {

    private fun TestScope.skillsRepo(
        session: ScriptedWsSession,
        requestTimeoutMs: Long = GatewayRpcClient.DEFAULT_REQUEST_TIMEOUT_MS,
    ): InstalledSkillRepositoryImpl {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val rpc = GatewayRpcClient.create(session, "ws://gw/ws", dispatcher, requestTimeoutMs)
        return InstalledSkillRepositoryImpl { rpc }
    }

    /** A `skills.status` answer whose payload is [payload] (JSON text). */
    private fun statusReply(payload: String): (String, JsonObject) -> JsonObject? = { method, _ ->
        if (method == "skills.status") {
            Json.parseToJsonElement("""{"type":"res","ok":true,"payload":$payload}""").jsonObject
        } else {
            null
        }
    }

    @Test fun `refresh maps skills status`() = runTest {
        val repo = skillsRepo(ScriptedWsSession(respond = fixtures("skills.status" to "res-skills-status")))
        val skills = repo.refreshFromGateway().getOrThrow()
        assertEquals(listOf("1password", "browser-automation", "weather"), skills.map { it.slug })
        assertTrue(skills[0].disabled)
        assertEquals(SkillSource.Local, skills[0].source)
        assertFalse(skills[1].disabled)
        assertEquals("", skills[1].installedVersion)
        assertEquals(SkillSource.ClawHub, skills[2].source)
        assertEquals("1.0.0", skills[2].installedVersion)
        assertEquals(1_792_035_309L, skills[2].installedAt)
        assertEquals(skills, repo.observeInstalled().first())
    }

    @Test fun `a status without a skills array is a protocol error`() = runTest {
        val bad = buildJsonObject {
            put("type", "res")
            put("ok", true)
            putJsonObject("payload") {}
        }
        val result = skillsRepo(ScriptedWsSession(respond = { _, _ -> bad })).refreshFromGateway()
        assertInstanceOf(GatewayProtocolException::class.java, result.exceptionOrNull())
    }

    @Test fun `a skill entry without a key fails the refresh with GatewayProtocolException`() = runTest {
        val session = ScriptedWsSession(respond = statusReply("""{"skills":[{"source":"openclaw-workspace"}]}"""))
        val repo = skillsRepo(session)
        assertInstanceOf(GatewayProtocolException::class.java, repo.refreshFromGateway().exceptionOrNull())
        assertTrue(repo.observeInstalled().first().isEmpty())
    }

    @Test fun `badly shaped refresh results fail with GatewayProtocolException`() = runTest {
        listOf(
            "[]",
            """{"skills":{}}""",
            """{"skills":[1]}""",
            """{"skills":[{"skillKey":{},"name":[]}]}""",
        ).forEach { shape ->
            val result = skillsRepo(ScriptedWsSession(respond = statusReply(shape))).refreshFromGateway()
            assertInstanceOf(GatewayProtocolException::class.java, result.exceptionOrNull(), shape)
        }
    }

    @Test fun `refresh timeout surfaces GatewayTimeoutException`() = runTest {
        val repo = skillsRepo(ScriptedWsSession(respond = { _, _ -> null }), requestTimeoutMs = 1_000)
        assertInstanceOf(GatewayTimeoutException::class.java, repo.refreshFromGateway().exceptionOrNull())
    }

    @Test fun `install sends the clawhub params and ends Succeeded with the refreshed entry`() = runTest {
        val session = ScriptedWsSession(
            respond = { method, _ ->
                when (method) {
                    "skills.install" -> GatewayFixtures.frame("res-skills-install")
                    "skills.status" -> GatewayFixtures.frame("res-skills-status")
                    else -> null
                }
            },
        )
        skillsRepo(session).install("@steipete/weather", "1.0.0").test {
            assertEquals(SkillInstallProgress.Connecting("@steipete/weather"), awaitItem())
            assertEquals(SkillInstallProgress.Applying("skills.install"), awaitItem())
            val installed = (awaitItem() as SkillInstallProgress.Succeeded).installed
            assertEquals("weather", installed.slug)
            assertEquals("1.0.0", installed.installedVersion)
            assertEquals(SkillSource.ClawHub, installed.source)
            awaitComplete()
        }
        val params = session.paramsOf("skills.install")
        assertEquals("clawhub", params.stringOrNull("source"))
        assertEquals("@steipete/weather", params.stringOrNull("slug"))
        assertEquals("1.0.0", params.stringOrNull("version"))
        assertEquals("false", params["force"].toString())
        assertEquals("120000", params["timeoutMs"].toString())
    }

    @Test fun `an install whose list refresh fails still ends Succeeded from the install answer`() = runTest {
        val session = ScriptedWsSession(respond = fixtures("skills.install" to "res-skills-install"))
        val progress = skillsRepo(session, requestTimeoutMs = 1_000).install("@steipete/weather", null).toList()
        val installed = (progress.last() as SkillInstallProgress.Succeeded).installed
        assertEquals("weather", installed.slug)
        assertEquals("1.0.0", installed.installedVersion)
        assertEquals(SkillSource.ClawHub, installed.source)
    }

    @Test fun `the stand-in entry of an install whose refresh failed lands in observeInstalled`() = runTest {
        val session = ScriptedWsSession(respond = fixtures("skills.install" to "res-skills-install"))
        val repo = skillsRepo(session, requestTimeoutMs = 1_000)
        val installed = (repo.install("@steipete/weather", null).toList().last() as SkillInstallProgress.Succeeded)
            .installed
        assertEquals(listOf(installed), repo.observeInstalled().first())
    }

    @Test fun `an unknown slug ends NotFound`() = runTest {
        val repo = skillsRepo(ScriptedWsSession(respond = fixtures("skills.install" to "err-skill-not-found")))
        repo.install("qa-nonexistent-skill-zz9", null).test {
            awaitItem()
            awaitItem()
            val expected = SkillInstallError.NotFound("qa-nonexistent-skill-zz9")
            assertEquals(SkillInstallProgress.Failed(expected), awaitItem())
            awaitComplete()
        }
    }

    @Test fun `a missing scope ends Unauthorized`() = runTest {
        val repo = skillsRepo(ScriptedWsSession(respond = fixtures("skills.install" to "err-missing-scope")))
        repo.install("a/b", null).test {
            awaitItem()
            awaitItem()
            assertEquals(SkillInstallProgress.Failed(SkillInstallError.Unauthorized("operator.read")), awaitItem())
            awaitComplete()
        }
    }

    @Test fun `install while disconnected ends NotConnected without a request`() = runTest {
        InstalledSkillRepositoryImpl { throw GatewayNotConnectedException() }.install("a/b", null).test {
            assertEquals(SkillInstallProgress.Connecting("a/b"), awaitItem())
            val failed = awaitItem() as SkillInstallProgress.Failed
            assertEquals(SkillInstallError.NotConnected, failed.error)
            assertFalse(failed.error.isRetryable)
            awaitComplete()
        }
    }

    @Test fun `install emits Failed Network when no session can be obtained`() = runTest {
        val repo = InstalledSkillRepositoryImpl { error("session provider broke") }

        val progress = repo.install("foo/bar").toList()

        assertTrue(progress.first() is SkillInstallProgress.Connecting)
        val network = (progress.last() as SkillInstallProgress.Failed).error as SkillInstallError.Network
        assertTrue(network.cause is IllegalStateException)
    }

    @Test fun `request timeout during install reports retryable Network`() = runTest {
        val session = ScriptedWsSession(respond = { _, _ -> null })
        // No response: virtual time runs to the install's own timeout.
        val failed = skillsRepo(session).install("foo/bar").toList().last() as SkillInstallProgress.Failed

        val network = failed.error as SkillInstallError.Network
        assertInstanceOf(GatewayTimeoutException::class.java, network.cause)
        assertTrue(failed.error.isRetryable)
    }

    @Test fun `cancelling an install propagates and emits no Failed`() = runTest {
        val session = ScriptedWsSession(respond = { _, _ -> null })
        val repo = skillsRepo(session)
        val seen = mutableListOf<SkillInstallProgress>()

        val job = launch { repo.install("foo/bar").collect { seen += it } }
        runCurrent()
        assertEquals(listOf("skills.install"), session.sent.map { it.stringOrNull("method") })
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertTrue(seen.none { it is SkillInstallProgress.Failed })
    }

    @Test fun `uninstall is unsupported and sends nothing`() = runTest {
        val session = ScriptedWsSession(respond = { _, _ -> null })
        val result = skillsRepo(session).uninstall("a/b")
        assertEquals("skills.uninstall", (result.exceptionOrNull() as GatewayMethodUnsupportedException).method)
        assertTrue(session.sent.isEmpty())
    }

    @Test fun `disable waits for config changed, then re-reads skills status`() = runTest {
        lateinit var session: ScriptedWsSession
        session = ScriptedWsSession(
            respond = { method, _ ->
                when (method) {
                    "skills.status" -> GatewayFixtures.frame("res-skills-status")
                    // Like the real gateway: config.changed can arrive before the skills.update response.
                    "skills.update" -> GatewayFixtures.frame("res-skills-update").also {
                        session.push(GatewayFixtures.frame("evt-config-changed"))
                    }
                    else -> null
                }
            },
        )
        val repo = skillsRepo(session)
        val started = testScheduler.currentTime
        assertTrue(repo.setSkillEnabled("browser-automation", enabled = false).isSuccess)
        // No virtual time passed: the early config.changed was observed (subscribed UNDISPATCHED before the
        // write), not masked by the 2 s cap.
        assertEquals(0L, testScheduler.currentTime - started)
        val params = session.paramsOf("skills.update")
        assertEquals("browser-automation", params.stringOrNull("skillKey"))
        assertEquals("false", params["enabled"].toString())
        val methods = session.sent.map { it.stringOrNull("method") }
        assertEquals(listOf("skills.status", "skills.update", "skills.status"), methods)
        assertEquals(3, repo.observeInstalled().first().size)
    }

    @Test fun `without config changed the list is re-read after the wait cap`() = runTest {
        val session = ScriptedWsSession(
            respond = fixtures("skills.status" to "res-skills-status", "skills.update" to "res-skills-update"),
        )
        val repo = skillsRepo(session)
        val started = testScheduler.currentTime
        assertTrue(repo.setSkillEnabled("browser-automation", enabled = false).isSuccess)
        assertEquals(2_000L, testScheduler.currentTime - started)
        val methods = session.sent.map { it.stringOrNull("method") }
        assertEquals(listOf("skills.status", "skills.update", "skills.status"), methods)
    }

    @Test fun `the socket ending during the config changed wait fails typed without waiting out the cap`() = runTest {
        val session = ScriptedWsSession(
            closeAfterReplyTo = setOf("skills.update"),
            respond = fixtures("skills.status" to "res-skills-status", "skills.update" to "res-skills-update"),
        )
        val repo = skillsRepo(session)
        val started = testScheduler.currentTime
        val result = repo.setSkillEnabled("browser-automation", enabled = false)
        assertInstanceOf(GatewayUnreachableException::class.java, result.exceptionOrNull())
        assertEquals(0L, testScheduler.currentTime - started)
    }

    @Test fun `an owner-qualified key is reduced to the bare skill key`() = runTest {
        val session = ScriptedWsSession(
            respond = fixtures("skills.status" to "res-skills-status", "skills.update" to "res-skills-update"),
        )
        assertTrue(skillsRepo(session).setSkillEnabled("@steipete/weather", enabled = true).isSuccess)
        val params = session.paramsOf("skills.update")
        assertEquals("weather", params.stringOrNull("skillKey"))
        assertEquals("true", params["enabled"].toString())
    }

    @Test fun `a key skills status does not list is refused without a write`() = runTest {
        val session = ScriptedWsSession(respond = fixtures("skills.status" to "res-skills-status"))
        val result = skillsRepo(session).setSkillEnabled("qa-does-not-exist", enabled = false)
        assertEquals(404, (result.exceptionOrNull() as GatewayApiException).statusCode)
        assertTrue(session.sent.none { it.stringOrNull("method") == "skills.update" })
    }

    @Test fun `a key from an earlier list is checked against a fresh skills status`() = runTest {
        val status = Json.parseToJsonElement(
            """{"type":"res","ok":true,"payload":{"skills":[{"skillKey":"only-here","source":"openclaw-extra"}]}}""",
        ).jsonObject
        var reply = GatewayFixtures.frame("res-skills-status")
        val session = ScriptedWsSession(respond = { method, _ -> if (method == "skills.status") reply else null })
        val repo = skillsRepo(session)
        repo.refreshFromGateway().getOrThrow()
        reply = status // the gateway no longer lists browser-automation
        val result = repo.setSkillEnabled("browser-automation", enabled = false)
        assertEquals(404, (result.exceptionOrNull() as GatewayApiException).statusCode)
        assertTrue(session.sent.none { it.stringOrNull("method") == "skills.update" })
    }

    @Test fun `a missing admin scope fails the toggle typed`() = runTest {
        val session = ScriptedWsSession(
            respond = fixtures("skills.status" to "res-skills-status", "skills.update" to "err-missing-scope"),
        )
        val result = skillsRepo(session).setSkillEnabled("browser-automation", enabled = false)
        assertInstanceOf(GatewayMissingScopeException::class.java, result.exceptionOrNull())
    }

    @Test fun `toggling while disconnected fails NotConnected`() = runTest {
        val result = InstalledSkillRepositoryImpl { throw GatewayNotConnectedException() }
            .setSkillEnabled("weather", enabled = false)
        assertInstanceOf(GatewayNotConnectedException::class.java, result.exceptionOrNull())
    }

    @Test fun `skills status entries map to SkillSource`() {
        assertEquals(SkillSource.ClawHub, skillSourceOf("openclaw-workspace", bundled = false, clawhub = true))
        assertEquals(SkillSource.Local, skillSourceOf("openclaw-workspace", bundled = false, clawhub = false))
        assertEquals(SkillSource.Local, skillSourceOf("openclaw-bundled", bundled = true, clawhub = false))
        assertEquals(SkillSource.Local, skillSourceOf("openclaw-extra", bundled = false, clawhub = false))
        assertEquals(SkillSource.Unknown, skillSourceOf("workspace", bundled = false, clawhub = false))
        assertEquals(SkillSource.Unknown, skillSourceOf(null, bundled = false, clawhub = false))
    }
}
