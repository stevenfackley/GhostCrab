package com.openclaw.ghostcrab.ui

import com.openclaw.ghostcrab.domain.exception.GatewayUnreachableException
import com.openclaw.ghostcrab.domain.model.AuthRequirement
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SessionChangesTest {

    private val url = "http://192.168.1.50:18789"
    private val session = GatewayConnection.Connected(
        url = url,
        displayName = "gw",
        version = "2026.10.3",
        authRequirement = AuthRequirement.Token,
        isHttps = false,
        capabilities = emptyList(),
        sessionGeneration = 1,
    )

    @Test fun `only the reconnecting emissions are dropped`() = runTest {
        val reconnected = session.copy(sessionGeneration = 2)
        val failed = GatewayConnection.Error(url, GatewayUnreachableException(url))
        val states = flowOf(
            session,
            session.copy(reconnecting = true),
            reconnected,
            reconnected.copy(reconnecting = true),
            failed,
            GatewayConnection.Disconnected,
        )

        val expected = listOf(session, reconnected, failed, GatewayConnection.Disconnected)
        assertEquals(expected, states.sessionChanges().toList())
    }
}
