package com.openclaw.ghostcrab.data.impl

import com.openclaw.ghostcrab.data.ws.FakeWsSession
import com.openclaw.ghostcrab.data.ws.GatewayWsClient
import com.openclaw.ghostcrab.domain.model.SkillInstallError
import com.openclaw.ghostcrab.domain.model.SkillInstallProgress
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InstalledSkillRepositoryImplTest {

    @Test
    fun `install emits progress notifications then Succeeded`() = runTest {
        val session = FakeWsSession()
        val ws = GatewayWsClient.forTesting(session, tokenProvider = { "tkn" })
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
        val ws = GatewayWsClient.forTesting(session, tokenProvider = { "tkn" })
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
}
