package com.openclaw.ghostcrab.testutil

import com.openclaw.ghostcrab.data.gateway.DeviceTokenStore
import com.openclaw.ghostcrab.data.gateway.StoredDeviceToken
import java.util.concurrent.ConcurrentHashMap

/** [DeviceTokenStore] backed by a map; [tokens] is open for assertions and seeding. */
class InMemoryDeviceTokenStore : DeviceTokenStore {
    val tokens = ConcurrentHashMap<String, StoredDeviceToken>()
    override suspend fun loadDeviceToken(gatewayKey: String): StoredDeviceToken? = tokens[gatewayKey]
    override suspend fun saveDeviceToken(gatewayKey: String, token: StoredDeviceToken) {
        tokens[gatewayKey] = token
    }
    override suspend fun clearDeviceToken(gatewayKey: String) {
        tokens.remove(gatewayKey)
    }
}
