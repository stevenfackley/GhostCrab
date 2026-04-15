package com.openclaw.ghostcrab.domain.repository

import com.openclaw.ghostcrab.domain.exception.ProfileNeedsReauthException
import com.openclaw.ghostcrab.domain.model.ConnectionProfile
import kotlinx.coroutines.flow.Flow

/**
 * CRUD operations for locally stored gateway connection profiles.
 *
 * Profile metadata (id, URL, display name, timestamps) persists in DataStore (Preferences).
 * Bearer tokens persist in EncryptedSharedPreferences (AES256_GCM_SPEC), keyed by profile id.
 *
 * **Contract frozen at v1.0.**
 */
interface ConnectionProfileRepository {

    /**
     * Emits the current list of saved profiles, updating on any change.
     *
     * @return A hot [Flow] that always replays the latest list on collection.
     */
    fun getProfiles(): Flow<List<ConnectionProfile>>

    /**
     * Saves or updates a profile. If [profile.id] already exists, it is overwritten.
     *
     * @param profile The profile metadata to persist.
     * @param token Bearer token to encrypt and store, or `null` to clear any existing token.
     */
    suspend fun saveProfile(profile: ConnectionProfile, token: String?)

    /**
     * Retrieves the decrypted bearer token for a profile.
     *
     * @param profileId The [ConnectionProfile.id] to look up.
     * @return The bearer token string, or `null` if none was stored.
     * @throws ProfileNeedsReauthException if the Keystore entry exists but decryption fails
     *   (e.g. after a factory reset). The corrupted entry is cleared before throwing.
     */
    suspend fun getToken(profileId: String): String?

    /**
     * Deletes a profile and its associated encrypted token.
     *
     * No-op if the profile does not exist.
     *
     * @param profileId The [ConnectionProfile.id] to delete.
     */
    suspend fun deleteProfile(profileId: String)
}
