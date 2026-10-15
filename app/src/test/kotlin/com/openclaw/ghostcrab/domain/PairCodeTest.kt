package com.openclaw.ghostcrab.domain

import com.openclaw.ghostcrab.domain.util.GatewayUrl
import com.openclaw.ghostcrab.domain.util.MAX_PAIR_CODE_LENGTH
import com.openclaw.ghostcrab.domain.util.PairCodeResult
import com.openclaw.ghostcrab.domain.util.decodePairCode
import com.openclaw.ghostcrab.domain.util.setupCodeExplanation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class PairCodeTest {

    private fun pairUri(json: String) =
        "ghostcrab://pair?p=" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

    @Test fun `v1 bundle decodes url token and name`() {
        val result = decodePairCode(pairUri("""{"v":1,"url":"http://192.168.0.23:18789","token":"tkn","name":"Den"}"""))
        val bundle = (result as PairCodeResult.Bundle).bundle
        assertEquals("http://192.168.0.23:18789", bundle.url.toString())
        assertEquals("tkn", bundle.token)
        assertEquals("Den", bundle.name)
        assertFalse(bundle.toString().contains("tkn"))
    }

    @Test fun `bundle without token is accepted`() {
        val result = decodePairCode(pairUri("""{"v":1,"url":"http://192.168.0.23:18789"}"""))
        assertNull((result as PairCodeResult.Bundle).bundle.token)
    }

    @Test fun `unknown version is rejected explicitly`() {
        val result = decodePairCode(pairUri("""{"v":2,"url":"http://192.168.0.23:18789"}"""))
        assertTrue((result as PairCodeResult.Invalid).reason.contains("version 2"))
    }

    @Test fun `malformed base64 is rejected`() {
        assertInstanceOf(PairCodeResult.Invalid::class.java, decodePairCode("ghostcrab://pair?p=%%%"))
    }

    @Test fun `non-http url inside the bundle is rejected`() {
        assertInstanceOf(PairCodeResult.Invalid::class.java, decodePairCode(pairUri("""{"v":1,"url":"ftp://x"}""")))
    }

    @Test fun `plain https url is still accepted`() {
        assertInstanceOf(PairCodeResult.PlainUrl::class.java, decodePairCode("https://abc-xyz.trycloudflare.com"))
    }

    @Test fun `random text is invalid`() {
        assertInstanceOf(PairCodeResult.Invalid::class.java, decodePairCode("just some random text"))
    }

    @Test fun `payload over the size cap is rejected before decoding`() {
        val name = "x".repeat(MAX_PAIR_CODE_LENGTH)
        val oversized = pairUri("""{"v":1,"url":"http://192.168.0.23:18789","name":"$name"}""")
        assertTrue((decodePairCode(oversized) as PairCodeResult.Invalid).reason.contains("too large"))
    }

    @Test fun `unknown version without a url still reports the version`() {
        val result = decodePairCode(pairUri("""{"v":2}"""))
        assertTrue((result as PairCodeResult.Invalid).reason.contains("version 2"))
    }

    @Test fun `bundle without url is rejected`() {
        assertInstanceOf(PairCodeResult.Invalid::class.java, decodePairCode(pairUri("""{"v":1,"token":"tkn"}""")))
    }

    @Test fun `userinfo inside the bundle url is rejected`() {
        val result = decodePairCode(pairUri("""{"v":1,"url":"http://user:pw@192.168.0.23:18789"}"""))
        assertInstanceOf(PairCodeResult.Invalid::class.java, result)
    }

    @Test fun `ghostcrab link without a payload is reported as pairing data, not as a QR url`() {
        val result = decodePairCode("ghostcrab://pair")
        assertFalse((result as PairCodeResult.Invalid).reason.contains("QR"))
    }

    @Test fun `a quoted version is rejected, not coerced`() {
        val result = decodePairCode(pairUri("""{"v":"1","url":"http://192.168.0.23:18789"}"""))
        assertInstanceOf(PairCodeResult.Invalid::class.java, result)
    }

    // Standard base64 uses '+' and '/'; the JSON below encodes to a payload containing both.
    private val plusSlashJson = """{"v":1,"url":"http://192.168.0.23:18789","name":"??>>??>>"}"""

    @Test fun `standard base64 payload with plus and slash is accepted`() {
        val std = Base64.getEncoder().encodeToString(plusSlashJson.toByteArray())
        assertTrue(std.contains('+') || std.contains('/'))
        val bundle = (decodePairCode("ghostcrab://pair?p=$std") as PairCodeResult.Bundle).bundle
        assertEquals("??>>??>>", bundle.name)
    }

    @Test fun `url-safe and standard encodings of the same payload decode identically`() {
        val urlSafe = (decodePairCode(pairUri(plusSlashJson)) as PairCodeResult.Bundle).bundle
        val std = Base64.getEncoder().encodeToString(plusSlashJson.toByteArray())
        val standard = (decodePairCode("ghostcrab://pair?p=$std") as PairCodeResult.Bundle).bundle
        assertEquals(urlSafe.url.toString(), standard.url.toString())
        assertEquals(urlSafe.name, standard.name)
    }

    // ── openclaw qr setup codes (ADR-0007): recognised and explained, never used ─────────────

    /** base64url of {"url":"ws://172.17.0.2:18789","bootstrapToken":"bt-test-0001","expiresAtMs":1792028000000}. */
    private val setupCode =
        "eyJ1cmwiOiJ3czovLzE3Mi4xNy4wLjI6MTg3ODkiLCJib290c3RyYXBUb2tlbiI6ImJ0LXRlc3QtMDAwMSIs" +
            "ImV4cGlyZXNBdE1zIjoxNzkyMDI4MDAwMDAwfQ"

    private fun encode(json: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

    @Test fun `a setup code is recognised with its gateway url and nothing else`() {
        val result = decodePairCode(setupCode) as PairCodeResult.SetupCode
        assertEquals("http://172.17.0.2:18789", result.url?.toString())
        assertFalse(result.toString().contains("bt-test-0001"))
    }

    @Test fun `oc-pair links are setup codes too`() {
        assertInstanceOf(PairCodeResult.SetupCode::class.java, decodePairCode("oc-pair://$setupCode"))
    }

    @Test fun `a wss setup code names the https gateway on 443`() {
        val code = encode("""{"url":"wss://gw.example/ws","bootstrapToken":"bt"}""")
        assertEquals("https://gw.example:443", (decodePairCode(code) as PairCodeResult.SetupCode).url?.toString())
    }

    @Test fun `the explanation says why and what to do instead`() {
        val text = setupCodeExplanation(GatewayUrl("http", "172.17.0.2", 18789))
        assertTrue(text.contains("http://172.17.0.2:18789"))
        assertTrue(text.contains("gateway token"))
        assertTrue(text.contains("openclaw devices approve"))
    }

    @Test fun `base64 json without a bootstrap token is not a setup code`() {
        assertInstanceOf(PairCodeResult.Invalid::class.java, decodePairCode(encode("""{"url":"ws://h:1"}""")))
    }

    @Test fun `a plain ws url is accepted as a gateway url`() {
        val result = decodePairCode("ws://192.168.1.50:18789") as PairCodeResult.PlainUrl
        assertEquals("http://192.168.1.50:18789", result.url.toString())
    }
}
