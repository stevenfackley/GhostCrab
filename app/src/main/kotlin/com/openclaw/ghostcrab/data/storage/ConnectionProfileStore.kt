package com.openclaw.ghostcrab.data.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.openclaw.ghostcrab.domain.exception.ProfileNeedsReauthException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.profileDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "connection_profiles",
)

private val KEY_PROFILES = stringPreferencesKey("profiles_json")

/**
 * Persists connection profile metadata in DataStore and bearer tokens in EncryptedSharedPreferences.
 *
 * Thread-safe. All suspend functions are safe to call from any dispatcher.
 */
class ConnectionProfileStore(private val context: Context) {

    // ── DataStore ─────────────────────────────────────────────────────────────

    fun getProfilesFlow(): Flow<List<StoredProfile>> =
        context.profileDataStore.data.map { prefs ->
            val json = prefs[KEY_PROFILES] ?: return@map emptyList()
            runCatching { Json.decodeFromString<List<StoredProfile>>(json) }.getOrElse { emptyList() }
        }

    suspend fun saveProfile(profile: StoredProfile) {
        context.profileDataStore.edit { prefs ->
            val current = run {
                val json = prefs[KEY_PROFILES] ?: return@run emptyList()
                runCatching { Json.decodeFromString<List<StoredProfile>>(json) }.getOrElse { emptyList() }
            }
            val updated = current.filter { it.id != profile.id } + profile
            prefs[KEY_PROFILES] = Json.encodeToString(updated)
        }
    }

    suspend fun deleteProfile(profileId: String) {
        context.profileDataStore.edit { prefs ->
            val current = run {
                val json = prefs[KEY_PROFILES] ?: return@run emptyList()
                runCatching { Json.decodeFromString<List<StoredProfile>>(json) }.getOrElse { emptyList() }
            }
            prefs[KEY_PROFILES] = Json.encodeToString(current.filter { it.id != profileId })
        }
        clearToken(profileId)
    }

    // ── EncryptedSharedPreferences ────────────────────────────────────────────

    /**
     * Stores [token] encrypted in the Android Keystore for [profileId].
     * Pass `null` to clear any existing token.
     */
    suspend fun saveToken(profileId: String, token: String?) = withContext(Dispatchers.IO) {
        val prefs = openEncryptedPrefs()
        if (token == null) {
            prefs.edit().remove(tokenKey(profileId)).apply()
        } else {
            prefs.edit().putString(tokenKey(profileId), token).apply()
        }
    }

    /**
     * Returns the decrypted token for [profileId], or `null` if none stored.
     *
     * @throws ProfileNeedsReauthException if decryption fails (e.g. factory reset). The
     *   corrupted entry is cleared before throwing.
     */
    suspend fun getToken(profileId: String): String? = withContext(Dispatchers.IO) {
        try {
            openEncryptedPrefs().getString(tokenKey(profileId), null)
        } catch (e: Exception) {
            // Keystore entry unreadable — clear it and force re-auth
            runCatching { clearToken(profileId) }
            throw ProfileNeedsReauthException(profileId)
        }
    }

    private fun clearToken(profileId: String) {
        runCatching {
            openEncryptedPrefs().edit().remove(tokenKey(profileId)).apply()
        }
    }

    private fun openEncryptedPrefs(): android.content.SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            "ghostcrab_tokens",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun tokenKey(profileId: String) = "token_$profileId"
}

// ── Storage DTO ───────────────────────────────────────────────────────────────

/**
 * Serializable representation of a profile stored in DataStore.
 * Mapped to/from [com.openclaw.ghostcrab.domain.model.ConnectionProfile] in the repository.
 */
@Serializable
data class StoredProfile(
    val id: String,
    val displayName: String,
    val url: String,
    val lastConnectedAt: Long?,
    val hasToken: Boolean,
)
