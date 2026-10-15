package com.openclaw.ghostcrab.data.impl

import com.openclaw.ghostcrab.domain.repository.ScopeProbeResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * ADR-0007: scopes come from `hello-ok.auth.scopes` of the live session. The former `auth.whoami` tests
 * (JSON-RPC result, `-32601` → UnknownOldGateway) are gone with the method: upstream never had it.
 */
class ScopeProbeImplTest {

    @Test fun `scopes of the live session are Known`() = runTest {
        val result = ScopeProbeImpl { setOf("operator.read", "operator.admin") }.probe()
        assertEquals(ScopeProbeResult.Known(setOf("operator.read", "operator.admin")), result)
        assertTrue((result as ScopeProbeResult.Known).has("operator.admin"))
    }

    @Test fun `an empty scope set is Known and empty, not unknown`() = runTest {
        assertEquals(ScopeProbeResult.Known(emptySet()), ScopeProbeImpl { emptySet() }.probe())
    }

    @Test fun `no session is Failed with the exception class`() = runTest {
        val result = ScopeProbeImpl { null }.probe()
        assertInstanceOf(ScopeProbeResult.Failed::class.java, result)
        assertTrue((result as ScopeProbeResult.Failed).cause.contains("GatewayNotConnectedException"))
    }
}
