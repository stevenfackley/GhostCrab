package com.openclaw.ghostcrab.data.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.openclaw.ghostcrab.domain.exception.ProfileNeedsReauthException
import com.openclaw.ghostcrab.domain.exception.ProfileStoreCorruptedException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.profileDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "connection_profiles",
)

private val KEY_PROFILES = stringPreferencesKey("profiles_json")

/** Tolerates fields written by newer app versions; never used to swallow errors on write. */
private val profileJson = Json { ignoreUnknownKeys = true }

/** Decodes the stored list for display. Undecodable data reads as empty and is never written back. */
internal fun decodeProfilesForRead(json: String?): List<StoredProfile> =
    json?.let { runCatching { profileJson.decodeFromString<List<StoredProfile>>(it) }.getOrNull() }.orEmpty()

/**
 * Decodes the stored list before a read-modify-write.
 *
 * @param json The raw stored value, or `null` when nothing was stored yet.
 * @return The stored profiles.
 * @throws ProfileStoreCorruptedException if non-null data cannot be decoded; the write must abort.
 */
internal fun decodeProfilesForWrite(json: String?): List<StoredProfile> = when (json) {
    null -> emptyList()
    else -> try {
        profileJson.decodeFromString<List<StoredProfile>>(json)
    } catch (e: IllegalArgumentException) { // SerializationException is an IllegalArgumentException
        throw ProfileStoreCorruptedException(e)
    }
}

/**
 * Persists connection profile metadata in DataStore and bearer tokens in EncryptedSharedPreferences.
 *
 * Thread-safe. All suspend functions are safe to call from any dispatcher.
 */
class ConnectionProfileStore(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    // Keystore init is 20–200ms and docs warn against multiple instances on the same file.
    // Injectable for testing — production uses the default lambda.
    private val encryptedPrefsFactory: (Context) -> SharedPreferences = { ctx ->
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            ctx,
            "ghostcrab_tokens",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    },
) {

    // ── DataStore ─────────────────────────────────────────────────────────────

    fun getProfilesFlow(): Flow<List<StoredProfile>> =
        context.profileDataStore.data.map { prefs -> decodeProfilesForRead(prefs[KEY_PROFILES]) }

    suspend fun saveProfile(profile: StoredProfile) {
        context.profileDataStore.edit { prefs ->
            val current = decodeProfilesForWrite(prefs[KEY_PROFILES])
            val updated = current.filter { it.id != profile.id } + profile
            prefs[KEY_PROFILES] = profileJson.encodeToString(updated)
        }
    }

    suspend fun deleteProfile(profileId: String) {
        context.profileDataStore.edit { prefs ->
            val current = decodeProfilesForWrite(prefs[KEY_PROFILES])
            prefs[KEY_PROFILES] = profileJson.encodeToString(current.filter { it.id != profileId })
        }
        clearToken(profileId)
    }

    // ── EncryptedSharedPreferences ────────────────────────────────────────────

    /**
     * Stores [token] encrypted in the Android Keystore for [profileId]. Pass `null` to clear it.
     *
     * @throws ProfileNeedsReauthException if the Keystore / encrypted prefs cannot be opened.
     */
    suspend fun saveToken(profileId: String, token: String?) = withContext(ioDispatcher) {
        try {
            val editor = encryptedPrefs.edit()
            if (token == null) editor.remove(tokenKey(profileId)) else editor.putString(tokenKey(profileId), token)
            editor.apply()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            throw ProfileNeedsReauthException(profileId, e)
        }
    }

    /**
     * Returns the decrypted token for [profileId], or `null` if none stored.
     *
     * @throws ProfileNeedsReauthException if decryption fails (e.g. factory reset). The
     *   corrupted entry is cleared before throwing.
     */
    suspend fun getToken(profileId: String): String? = withContext(ioDispatcher) {
        try {
            encryptedPrefs.getString(tokenKey(profileId), null)
        } catch (e: Exception) {
            // Keystore entry unreadable — clear it and force re-auth
            runCatching { clearToken(profileId) }
            throw ProfileNeedsReauthException(profileId, e)
        }
    }

    private fun clearToken(profileId: String) {
        runCatching {
            encryptedPrefs.edit().remove(tokenKey(profileId)).apply()
        }
    }

    private val encryptedPrefs: SharedPreferences by lazy { encryptedPrefsFactory(context) }

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
