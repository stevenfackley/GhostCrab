package com.openclaw.ghostcrab.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * Persists app-level user preferences.
 *
 * **Contract frozen at v1.0.**
 */
interface SettingsRepository {

    /**
     * Emits whether the user has opted in to HTTP connections to public (non-LAN) IP addresses.
     *
     * Default: `false` (cleartext to public IPs is blocked by default).
     */
    val allowCleartextPublicIPs: Flow<Boolean>

    /**
     * Persists the "allow cleartext to public IPs" preference.
     *
     * @param enabled `true` to allow HTTP connections to public IPs.
     */
    suspend fun setAllowCleartextPublicIPs(enabled: Boolean)
}
