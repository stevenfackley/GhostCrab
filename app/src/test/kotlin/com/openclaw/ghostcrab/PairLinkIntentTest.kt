package com.openclaw.ghostcrab

import android.content.Intent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PairLinkIntentTest {

    private val link = "ghostcrab://pair?p=abc"

    @Test fun `view of the ghostcrab scheme is handled`() {
        assertEquals(link, pairLinkToHandle(Intent.ACTION_VIEW, "ghostcrab", 0, link))
    }

    @Test fun `relaunch from recents is ignored`() {
        assertNull(pairLinkToHandle(Intent.ACTION_VIEW, "ghostcrab", Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY, link))
    }

    @Test fun `launcher intent and other schemes are ignored`() {
        assertNull(pairLinkToHandle(Intent.ACTION_MAIN, null, 0, null))
        assertNull(pairLinkToHandle(Intent.ACTION_VIEW, "https", 0, "https://gw.example"))
        assertNull(pairLinkToHandle(Intent.ACTION_SEND, "ghostcrab", 0, link))
    }
}
