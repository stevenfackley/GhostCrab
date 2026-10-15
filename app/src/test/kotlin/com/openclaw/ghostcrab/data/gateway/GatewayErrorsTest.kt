package com.openclaw.ghostcrab.data.gateway

import com.openclaw.ghostcrab.domain.exception.GatewayConnectAuthException
import com.openclaw.ghostcrab.domain.exception.GatewayMethodUnsupportedException
import com.openclaw.ghostcrab.domain.exception.GatewayMissingScopeException
import com.openclaw.ghostcrab.domain.exception.GatewayPairingRequiredException
import com.openclaw.ghostcrab.domain.exception.GatewayRpcException
import com.openclaw.ghostcrab.testutil.GatewayFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GatewayErrorsTest {

    private val url = "ws://192.168.1.50:18789/ws"

    private fun errorOf(fixture: String): ErrorShape =
        requireNotNull((GatewayFrameCodec.decode(GatewayFixtures.text(fixture)) as GatewayFrame.Response).error)

    @Test fun `PAIRING_REQUIRED carries the request id, reason and scopes`() {
        val e = errorOf("err-pairing-required").toGatewayException(url, "connect")
        assertInstanceOf(GatewayPairingRequiredException::class.java, e)
        e as GatewayPairingRequiredException
        assertEquals("af0b5d81-7bd2-4b86-80b0-4a489b541974", e.requestId)
        assertEquals("not-paired", e.reason)
        assertEquals(listOf("operator.read", "operator.write", "operator.admin"), e.requestedScopes)
        assertTrue(e.isRetryable)
        assertTrue(e.message.orEmpty().contains("openclaw devices approve af0b5d81-7bd2-4b86-80b0-4a489b541974"))
    }

    @Test fun `token mismatch is a connect-auth error naming the url and code`() {
        val e = errorOf("err-token-mismatch").toGatewayException(url, "connect") as GatewayConnectAuthException
        assertEquals("AUTH_TOKEN_MISMATCH", e.detailCode)
        assertEquals("update_auth_credentials", e.recommendedNextStep)
        assertFalse(e.isRetryable)
        assertTrue(e.message.orEmpty().contains(url))
        assertTrue(e.message.orEmpty().contains("AUTH_TOKEN_MISMATCH"))
    }

    @Test fun `token missing and device identity required are connect-auth errors`() {
        assertEquals(
            "AUTH_TOKEN_MISSING",
            (errorOf("err-token-missing").toGatewayException(url, "connect") as GatewayConnectAuthException).detailCode,
        )
        assertEquals(
            "DEVICE_IDENTITY_REQUIRED",
            (errorOf("err-device-identity-required").toGatewayException(url, "connect") as GatewayConnectAuthException)
                .detailCode,
        )
    }

    @Test fun `MISSING_SCOPE names the scope`() {
        val e = errorOf("err-missing-scope").toGatewayException(url, "status") as GatewayMissingScopeException
        assertEquals("operator.read", e.missingScope)
        assertEquals("status", e.method)
    }

    @Test fun `legacy missing-scope message without details still maps`() {
        val e = ErrorShape(code = "FORBIDDEN", message = "missing scope: operator.admin")
            .toGatewayException(url, "config.get") as GatewayMissingScopeException
        assertEquals("operator.admin", e.missingScope)
    }

    @Test fun `unknown method is unsupported, not a generic error`() {
        val e = errorOf("err-unknown-method").toGatewayException(url, "skills.list")
        assertInstanceOf(GatewayMethodUnsupportedException::class.java, e)
        assertEquals("skills.list", (e as GatewayMethodUnsupportedException).method)
    }

    @Test fun `anything else is a generic rpc error with the server text`() {
        val e = errorOf("err-skill-not-found").toGatewayException(url, "skills.install") as GatewayRpcException
        assertEquals("UNAVAILABLE", e.code)
        assertNull(e.detailCode)
        assertTrue(e.rpcMessage.startsWith("Skill \"qa-nonexistent-skill-zz9\" not found on ClawHub"))
        assertFalse(e.isRetryable)
    }

    @Test fun `the retryable hint is honoured`() {
        val e = ErrorShape(code = "UNAVAILABLE", message = "startup", retryable = true)
            .toGatewayException(url, "connect")
        assertTrue(e.isRetryable)
    }
}
