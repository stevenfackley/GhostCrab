package com.openclaw.ghostcrab.data.gateway

import com.openclaw.ghostcrab.domain.exception.GatewayConnectAuthException
import com.openclaw.ghostcrab.domain.exception.GatewayMethodUnsupportedException
import com.openclaw.ghostcrab.domain.exception.GatewayMissingScopeException
import com.openclaw.ghostcrab.domain.exception.GatewayPairingRequiredException
import com.openclaw.ghostcrab.domain.exception.GatewayRpcException
import com.openclaw.ghostcrab.testutil.GatewayFixtures
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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

    @Test fun `a metadata-upgrade re-pairing carries its own request id and reason`() {
        val e = errorOf("err-pairing-metadata-upgrade").toGatewayException(url, "connect")
            as GatewayPairingRequiredException
        assertEquals("6837c6b0-f559-445b-bc0c-a50fbe5b5998", e.requestId)
        assertEquals("metadata-upgrade", e.reason)
        assertEquals(listOf("operator.read", "operator.write", "operator.admin"), e.requestedScopes)
        assertTrue(e.isRetryable)
    }

    @Test fun `a client id outside the allow-list is a non-retryable rpc error`() {
        val e = errorOf("err-client-id-invalid").toGatewayException(url, "connect") as GatewayRpcException
        assertEquals("INVALID_REQUEST", e.code)
        assertNull(e.detailCode)
        assertTrue(e.rpcMessage.contains("/client/id"))
        assertFalse(e.isRetryable)
    }

    @Test fun `a generic rpc error keeps the structured details`() {
        val e = errorOf("err-config-invalid").toGatewayException(url, "config.patch") as GatewayRpcException
        val issue = e.details?.get("issues")?.jsonArray?.single()?.jsonObject
        assertEquals("gateway.port", issue?.get("path")?.jsonPrimitive?.content)
    }

    @Test fun `a generic rpc error carries the retry hints`() {
        val e = ErrorShape(code = "UNAVAILABLE", message = "starting", retryable = true, retryAfterMs = 2_000)
            .toGatewayException(url, "health") as GatewayRpcException
        assertTrue(e.isRetryable)
        assertEquals(2_000L, e.retryAfterMs)
    }

    @Test fun `a connect-auth rejection honours wait_then_retry and the retry hints`() {
        // Synthetic: no captured frame yet for a rate-limited connect.
        val waited = ErrorShape(
            code = "UNAVAILABLE",
            message = "too many attempts",
            details = buildJsonObject {
                put("code", "AUTH_RATE_LIMITED")
                put("recommendedNextStep", "wait_then_retry")
            },
            retryAfterMs = 30_000,
        ).toGatewayException(url, "connect") as GatewayConnectAuthException
        assertTrue(waited.isRetryable)
        assertEquals(30_000L, waited.retryAfterMs)

        val hinted = ErrorShape(
            code = "INVALID_REQUEST",
            message = "unauthorized",
            details = buildJsonObject { put("code", "AUTH_TOKEN_MISMATCH") },
            retryable = true,
        ).toGatewayException(url, "connect") as GatewayConnectAuthException
        assertTrue(hinted.isRetryable)
    }

    @Test fun `the retryable hint is honoured`() {
        val e = ErrorShape(code = "UNAVAILABLE", message = "startup", retryable = true)
            .toGatewayException(url, "connect")
        assertTrue(e.isRetryable)
    }
}
