package com.openclaw.ghostcrab.data.impl

import com.openclaw.ghostcrab.data.ws.FakeWsSession
import com.openclaw.ghostcrab.data.ws.GatewayWsClient
import com.openclaw.ghostcrab.domain.exception.GatewayNotConnectedException
import com.openclaw.ghostcrab.domain.exception.GatewayTimeoutException
import com.openclaw.ghostcrab.domain.exception.GatewayProtocolException
import com.openclaw.ghostcrab.domain.model.SkillInstallError
import com.openclaw.ghostcrab.domain.model.SkillInstallProgress
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InstalledSkillRepositoryImplTest {

    @Test
    fun `install emits progress notifications then Succeeded`() = runTest {
        val session = FakeWsSession()
        val ws = GatewayWsClient.forTesting(session)
        val repo = InstalledSkillRepositoryImpl { ws }

        val emissions = async {
            repo.install("wanng-ide/auto-skill-hunter").toList()
        }

        val sent = session.awaitSent()
        val id = sent["id"]!!.toString().toLong()

        session.pushInbound(
            """{"jsonrpc":"2.0","method":"skills.install.progress","params":{"phase":"downloading","pct":40}}"""
        )
        val resultJson = """{"jsonrpc":"2.0","id":$id,"result":""" +
            """{"slug":"wanng-ide/auto-skill-hunter","installed_version":"1.0.0",""" +
            """"source":"ClawHub","installed_at":1234567890}}"""
        session.pushInbound(resultJson)

        val progress = emissions.await()
        assertTrue(progress.first() is SkillInstallProgress.Connecting)
        assertTrue(progress.any { it is SkillInstallProgress.Downloading && it.pct == 40 })
        val last = progress.last() as SkillInstallProgress.Succeeded
        assertEquals("wanng-ide/auto-skill-hunter", last.installed.slug)
    }

    @Test
    fun `install maps -32003 error to Unauthorized`() = runTest {
        val session = FakeWsSession()
        val ws = GatewayWsClient.forTesting(session)
        val repo = InstalledSkillRepositoryImpl { ws }

        val emissions = async { repo.install("foo/bar").toList() }
        val sent = session.awaitSent()
        val id = sent["id"]!!.toString().toLong()
        session.pushInbound(
            """{"jsonrpc":"2.0","id":$id,"error":{"code":-32003,"message":"missing scope"}}"""
        )

        val last = emissions.await().last()
        assertTrue(last is SkillInstallProgress.Failed)
        val err = (last as SkillInstallProgress.Failed).error
        assertTrue(err is SkillInstallError.Unauthorized)
        assertEquals("operator.admin", (err as SkillInstallError.Unauthorized).missingScope)
    }

    @Test
    fun `install emits Failed Network when no session can be opened`() = runTest {
        val repo = InstalledSkillRepositoryImpl { error("Cannot install skills while disconnected") }

        val progress = repo.install("foo/bar").toList()

        assertTrue(progress.first() is SkillInstallProgress.Connecting)
        val failed = progress.last() as SkillInstallProgress.Failed
        assertTrue(failed.error is SkillInstallError.Network)
    }

    @Test
    fun `install while disconnected reports non-retryable NotConnected`() = runTest {
        val repo = InstalledSkillRepositoryImpl { throw GatewayNotConnectedException() }

        val failed = repo.install("foo/bar").toList().last() as SkillInstallProgress.Failed

        assertEquals(SkillInstallError.NotConnected, failed.error)
        assertFalse(failed.error.isRetryable)
    }

    @Test
    fun `refreshFromGateway publishes the gateway list`() = runTest {
        val session = FakeWsSession()
        val repo = InstalledSkillRepositoryImpl { GatewayWsClient.forTesting(session) }

        val call = async { repo.refreshFromGateway() }
        val id = session.awaitSent()["id"].toString().toLong()
        session.pushInbound(
            """{"jsonrpc":"2.0","id":$id,"result":{"skills":[{"slug":"a/b","installed_version":"1.0"}]}}""",
        )

        assertEquals(listOf("a/b"), call.await().getOrThrow().map { it.slug })
        assertEquals(listOf("a/b"), repo.observeInstalled().first().map { it.slug })
    }

    @Test
    fun `malformed skill entry fails the refresh with GatewayProtocolException`() = runTest {
        val session = FakeWsSession()
        val repo = InstalledSkillRepositoryImpl { GatewayWsClient.forTesting(session) }

        val call = async { repo.refreshFromGateway() }
        val id = session.awaitSent()["id"].toString().toLong()
        session.pushInbound("""{"jsonrpc":"2.0","id":$id,"result":{"skills":[{"installed_version":"1.0"}]}}""")

        assertInstanceOf(GatewayProtocolException::class.java, call.await().exceptionOrNull())
    }

    @Test
    fun `uninstall error is returned as a failure`() = runTest {
        val session = FakeWsSession()
        val repo = InstalledSkillRepositoryImpl { GatewayWsClient.forTesting(session) }

        val call = async { repo.uninstall("a/b") }
        val id = session.awaitSent()["id"].toString().toLong()
        session.pushInbound("""{"jsonrpc":"2.0","id":$id,"error":{"code":-32004,"message":"a/b"}}""")

        assertInstanceOf(GatewayWsClient.WsRpcException::class.java, call.await().exceptionOrNull())
    }

    @Test
    fun `install with a malformed result reports a MalformedResponse error`() = runTest {
        val session = FakeWsSession()
        val repo = InstalledSkillRepositoryImpl { GatewayWsClient.forTesting(session) }

        val emissions = async { repo.install("foo/bar").toList() }
        val id = session.awaitSent()["id"].toString().toLong()
        session.pushInbound("""{"jsonrpc":"2.0","id":$id,"result":{"installed_version":"1.0.0"}}""")

        val last = emissions.await().last() as SkillInstallProgress.Failed
        assertInstanceOf(SkillInstallError.MalformedResponse::class.java, last.error)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.refreshWith(result: String): Throwable? {
        val session = FakeWsSession()
        val repo = InstalledSkillRepositoryImpl { GatewayWsClient.forTesting(session) }
        val call = async { repo.refreshFromGateway() }
        val id = session.awaitSent()["id"].toString().toLong()
        session.pushInbound("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
        return call.await().exceptionOrNull()
    }

    @Test
    fun `badly shaped refresh results fail with GatewayProtocolException`() = runTest {
        listOf(
            "[]",
            """{"skills":{}}""",
            """{"skills":[{"slug":{},"installed_version":"1"}]}""",
        ).forEach { shape ->
            val e = refreshWith(shape)
            assertInstanceOf(GatewayProtocolException::class.java, e, shape)
        }
    }

    @Test
    fun `request timeout during install reports retryable Network`() = runTest {
        val session = FakeWsSession()
        val repo = InstalledSkillRepositoryImpl { GatewayWsClient.forTesting(session, requestTimeoutMs = 1_000) }

        val emissions = async { repo.install("foo/bar").toList() }
        session.awaitSent()
        // No response is pushed: virtual time advances to the request timeout.
        val failed = emissions.await().last() as SkillInstallProgress.Failed

        assertInstanceOf(SkillInstallError.Network::class.java, failed.error)
        assertTrue(failed.error.isRetryable)
    }

    @Test
    fun `refresh timeout surfaces GatewayTimeoutException`() = runTest {
        val session = FakeWsSession()
        val repo = InstalledSkillRepositoryImpl { GatewayWsClient.forTesting(session, requestTimeoutMs = 1_000) }

        val call = async { repo.refreshFromGateway() }
        session.awaitSent()

        assertInstanceOf(GatewayTimeoutException::class.java, call.await().exceptionOrNull())
    }

    @Test
    fun `cancelling an install propagates and emits no Failed`() = runTest {
        val session = FakeWsSession()
        val repo = InstalledSkillRepositoryImpl { GatewayWsClient.forTesting(session) }
        val seen = mutableListOf<SkillInstallProgress>()

        val job = launch { repo.install("foo/bar").collect { seen += it } }
        session.awaitSent()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertTrue(seen.none { it is SkillInstallProgress.Failed })
    }
}
