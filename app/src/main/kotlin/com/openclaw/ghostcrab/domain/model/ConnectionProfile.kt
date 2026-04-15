package com.openclaw.ghostcrab.domain.model

/**
 * A saved gateway connection profile stored locally.
 *
 * Metadata persists in DataStore (Preferences). The bearer token, if any,
 * is stored separately in EncryptedSharedPreferences keyed by [id].
 *
 * @param id UUID string unique to this profile.
 * @param displayName User-visible label (defaulted to hostname:port on creation).
 * @param url Base URL of the gateway.
 * @param lastConnectedAt Epoch millis of last successful connection, or `null` if never.
 * @param hasToken Whether a token is stored in EncryptedSharedPreferences for this profile.
 */
data class ConnectionProfile(
    val id: String,
    val displayName: String,
    val url: String,
    val lastConnectedAt: Long?,
    val hasToken: Boolean,
)
