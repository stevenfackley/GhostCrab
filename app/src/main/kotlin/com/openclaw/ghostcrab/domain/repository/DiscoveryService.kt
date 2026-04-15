package com.openclaw.ghostcrab.domain.repository

import com.openclaw.ghostcrab.domain.model.DiscoveredGateway
import kotlinx.coroutines.flow.Flow

/**
 * Discovers OpenClaw Gateway instances on the local network via mDNS (NsdManager).
 *
 * Service type: `_openclaw-gw._tcp.` Default port: 18789.
 *
 * **Contract frozen at v1.0.**
 */
interface DiscoveryService {

    /**
     * Starts mDNS discovery and emits each resolved gateway as it is found.
     *
     * - Acquires a `WifiManager.MulticastLock` before starting.
     * - Deduplicates by `instanceName`; subsequent resolutions of the same instance are ignored.
     * - The flow completes when [stopDiscovery] is called or after the caller's collection scope
     *   is cancelled.
     *
     * @return A cold [Flow] that emits [DiscoveredGateway] instances as they are resolved.
     *   Does not throw; resolution failures for individual services are silently skipped.
     */
    fun startDiscovery(): Flow<DiscoveredGateway>

    /**
     * Stops any active mDNS discovery and releases the multicast lock.
     *
     * Safe to call when discovery is not active.
     */
    suspend fun stopDiscovery()
}
