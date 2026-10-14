package com.openclaw.ghostcrab.domain

import com.openclaw.ghostcrab.domain.util.PairCodeResult
import com.openclaw.ghostcrab.domain.util.decodePairCode
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
}
