package com.openclaw.ghostcrab.ui

import com.openclaw.ghostcrab.domain.model.GatewayConnection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNot

/**
 * The connection state as screens that load per session should see it: the transient
 * `Connected(reconnecting = true)` emission of the manager's silent reconnect is dropped, so a socket blip neither
 * starts loads that are bound to fail nor resets screen state (unsaved edits included). The screen keeps treating
 * the session as connected; the reconnected session arrives as a new [GatewayConnection.Connected] (next
 * `sessionGeneration`) and is the one that triggers a reload. A failed reconnect still arrives as
 * [GatewayConnection.Error].
 *
 * @receiver `GatewayConnectionManager.connectionState`.
 * @return The same states without the reconnecting ones.
 */
fun Flow<GatewayConnection>.sessionChanges(): Flow<GatewayConnection> =
    filterNot { it is GatewayConnection.Connected && it.reconnecting }
