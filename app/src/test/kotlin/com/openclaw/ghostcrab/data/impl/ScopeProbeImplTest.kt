package com.openclaw.ghostcrab.data.impl

import com.openclaw.ghostcrab.data.ws.FakeWsSession
import com.openclaw.ghostcrab.data.ws.GatewayWsClient
import com.openclaw.ghostcrab.domain.repository.ScopeProbeResult
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScopeProbeImplTest {

    @Test
    fun `probe parses scopes from auth_whoami response`() = runTest {
        val session = FakeWsSession()
        val ws = GatewayWsClient.forTesting(session, tokenProvider = { "tkn" })
        val probe = ScopeProbeImpl { ws }

        val result = async { probe.probe() }
        val sent = session.awaitSent()
        val id = sent["id"]!!.toString().toLong()
        session.pushInbound(
            """{"jsonrpc":"2.0","id":$id,"result":{"scopes":["operator.read","operator.admin"]}}"""
        )

        val known = result.await() as ScopeProbeResult.Known
        assertTrue(known.has("operator.admin"))
        assertEquals(setOf("operator.read", "operator.admin"), known.scopes)
    }

    @Test
    fun `probe maps method-not-found to UnknownOldGateway`() = runTest {
        val session = FakeWsSession()
        val ws = GatewayWsClient.forTesting(session, tokenProvider = { "tkn" })
        val probe = ScopeProbeImpl { ws }

        val result = async { probe.probe() }
        val sent = session.awaitSent()
        val id = sent["id"]!!.toString().toLong()
        session.pushInbound(
            """{"jsonrpc":"2.0","id":$id,"error":{"code":-32601,"message":"method not found"}}"""
        )

        assertTrue(result.await() is ScopeProbeResult.UnknownOldGateway)
    }
}
