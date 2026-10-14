package com.openclaw.ghostcrab.ui

import com.openclaw.ghostcrab.domain.util.GatewayUrl
import com.openclaw.ghostcrab.domain.util.PairBundle
import com.openclaw.ghostcrab.ui.connection.PairingHandoff
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

class PairingHandoffTest {

    private val bundle = PairBundle(GatewayUrl("http", "192.168.0.23", 18789), token = "tkn", name = null)

    @Test fun `take returns the offered bundle exactly once`() {
        val handoff = PairingHandoff()
        handoff.offer(bundle)
        assertEquals(bundle, handoff.take())
        assertNull(handoff.take())
    }

    @Test fun `bundle within the ttl is returned`() {
        val clock = TestTimeSource()
        val handoff = PairingHandoff(clock, ttl = 2.minutes)
        handoff.offer(bundle)
        clock += 2.minutes - 1.milliseconds
        assertEquals(bundle, handoff.take())
    }

    @Test fun `bundle older than the ttl is dropped on read`() {
        val clock = TestTimeSource()
        val handoff = PairingHandoff(clock, ttl = 2.minutes)
        handoff.offer(bundle)
        clock += 2.minutes + 1.milliseconds
        assertNull(handoff.take())
        assertNull(handoff.take())
    }

    @Test fun `every offer bumps the offers counter`() {
        val handoff = PairingHandoff()
        handoff.offer(bundle)
        handoff.offer(bundle)
        assertEquals(2, handoff.offers.value)
    }
}
