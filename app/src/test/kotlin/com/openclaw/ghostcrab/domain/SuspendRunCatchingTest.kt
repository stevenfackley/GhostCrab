package com.openclaw.ghostcrab.domain

import com.openclaw.ghostcrab.domain.exception.GatewayTimeoutException
import com.openclaw.ghostcrab.domain.util.suspendRunCatching
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.coroutines.cancellation.CancellationException

class SuspendRunCatchingTest {

    @Test
    fun `returns the value on success`() = runTest {
        assertEquals(42, suspendRunCatching { 42 }.getOrThrow())
    }

    @Test
    fun `captures ordinary failures`() = runTest {
        val result = suspendRunCatching { throw GatewayTimeoutException("http://x") }
        assertInstanceOf(GatewayTimeoutException::class.java, result.exceptionOrNull())
    }

    @Test
    fun `rethrows cancellation instead of capturing it`() = runTest {
        assertThrows<CancellationException> { suspendRunCatching { throw CancellationException("cancelled") } }
    }
}
