package com.openclaw.ghostcrab.data.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.openclaw.ghostcrab.data.gateway.DeviceTokenStore
import com.openclaw.ghostcrab.data.gateway.StoredDeviceToken
import com.openclaw.ghostcrab.data.gateway.gatewayKeyOf
import com.openclaw.ghostcrab.domain.exception.DeviceCredentialStorageException
import com.openclaw.ghostcrab.domain.exception.ProfileNeedsReauthException
import com.openclaw.ghostcrab.domain.exception.TokenWipeFailedException
import com.openclaw.ghostcrab.domain.exception.ProfileStoreCorruptedException
import com.openclaw.ghostcrab.domain.util.GatewayUrl
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

private val Context.profileDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "connection_profiles",
)

internal val KEY_PROFILES = stringPreferencesKey("profiles_json")

/** Holds the raw blob of the last undecodable profiles value, so a recovery write loses nothing. */
internal val KEY_PROFILES_CORRUPT_BACKUP = stringPreferencesKey("profiles_json_corrupt_backup")

/** Tolerates fields written by newer app versions; never used to swallow errors on write. */
private val profileJson = Json { ignoreUnknownKeys = true }

/** Decodes the stored list for display. Undecodable data reads as empty and is never written back. */
internal fun decodeProfilesForRead(json: String?): List<StoredProfile> =
    json?.let { runCatching { profileJson.decodeFromString<List<StoredProfile>>(it) }.getOrNull() }.orEmpty()

/** Maps raw preferences to profiles; a DataStore [IOException] reads as empty (fail closed), others propagate. */
internal fun Flow<Preferences>.toProfilesFlow(): Flow<List<StoredProfile>> =
    map { prefs -> decodeProfilesForRead(prefs[KEY_PROFILES]) }
        .catch { e -> if (e is IOException) emit(emptyList()) else throw e }

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
 * Reads the stored list before a read-modify-write, recovering from undecodable data.
 *
 * If the stored value cannot be decoded (corruption or a newer schema) the raw blob is moved to
 * [KEY_PROFILES_CORRUPT_BACKUP] (replacing any older backup) and the list restarts empty, so the
 * write proceeds, the user is unblocked and the old data is kept rather than overwritten.
 *
 * @return The decodable stored profiles, or an empty list after a recovery.
 */
internal fun MutablePreferences.profilesForWrite(): List<StoredProfile> {
    val raw = this[KEY_PROFILES]
    return try {
        decodeProfilesForWrite(raw)
    } catch (_: ProfileStoreCorruptedException) {
        if (raw != null) this[KEY_PROFILES_CORRUPT_BACKUP] = raw
        remove(KEY_PROFILES)
        emptyList()
    }
}

/** Removes the profile list and its corruption backup unconditionally. */
internal fun MutablePreferences.clearProfileKeys() {
    remove(KEY_PROFILES)
    remove(KEY_PROFILES_CORRUPT_BACKUP)
}

/**
 * Persists connection profile metadata in DataStore and bearer tokens in EncryptedSharedPreferences.
 *
 * Thread-safe. All suspend functions are safe to call from any dispatcher.
 *
 * Writes never discard an undecodable profile list: it is moved to a backup key first (see
 * [profilesForWrite]); [clearAll] is the only operation that removes the backup.
 *
 * Also the app's [DeviceTokenStore] (ADR-0007): device tokens live in the same encrypted file as the profile
 * tokens (one EncryptedSharedPreferences instance, shared with [EncryptedDeviceTokenStore]), so [clearAll] wipes
 * them too.
 */
class ConnectionProfileStore private constructor(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher,
    encryptedPrefsHolder: Lazy<SharedPreferences>,
) : DeviceTokenStore by EncryptedDeviceTokenStore(encryptedPrefsHolder, ioDispatcher) {

    /**
     * @param context Application context.
     * @param ioDispatcher Dispatcher for the blocking EncryptedSharedPreferences I/O.
     * @param encryptedPrefsFactory Opens the encrypted token file (called once, lazily).
     */
    constructor(
        context: Context,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        // Keystore init is 20–200ms and docs warn against multiple instances on the same file.
        // Injectable for testing — production uses the default lambda.
        encryptedPrefsFactory: (Context) -> SharedPreferences = { ctx ->
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
    ) : this(context, ioDispatcher, lazy { encryptedPrefsFactory(context) })

    // ── DataStore ─────────────────────────────────────────────────────────────

    fun getProfilesFlow(): Flow<List<StoredProfile>> =
        context.profileDataStore.data.toProfilesFlow()

    suspend fun saveProfile(profile: StoredProfile) {
        context.profileDataStore.edit { prefs ->
            val current = prefs.profilesForWrite()
            val updated = current.filter { it.id != profile.id } + profile
            prefs[KEY_PROFILES] = profileJson.encodeToString(updated)
        }
    }

    /**
     * Removes the profile, its token and, when no other saved profile uses the same gateway, that gateway's device
     * token. Token removal is best effort (the contract has no failure path); a leftover entry stays encrypted and
     * Clear all wipes it.
     *
     * @param profileId The profile to delete; no-op for an unknown id.
     */
    suspend fun deleteProfile(profileId: String) {
        var orphanedGateway: String? = null
        context.profileDataStore.edit { prefs ->
            val current = prefs.profilesForWrite()
            orphanedGateway = orphanedGatewayKey(current, profileId)
            prefs[KEY_PROFILES] = profileJson.encodeToString(current.filter { it.id != profileId })
        }
        clearToken(profileId)
        orphanedGateway?.let { key ->
            try {
                clearDeviceToken(key)
            } catch (ignored: DeviceCredentialStorageException) {
                // Best effort, like clearToken: deleting the profile must not fail on the Keystore.
            }
        }
    }

    /**
     * Removes every saved profile, the corruption backup (if any) and all stored tokens, even when
     * the stored profile list cannot be decoded.
     *
     * @throws TokenWipeFailedException if the encrypted token store cannot be wiped (the
     *   profile list is already gone by then, so the caller must surface that tokens may remain).
     */
    suspend fun clearAll() {
        context.profileDataStore.edit { prefs -> prefs.clearProfileKeys() }
        wipeTokens()
    }

    internal suspend fun wipeTokens() = withContext(ioDispatcher) {
        val committed = try {
            encryptedPrefs.edit().clear().commit()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            throw TokenWipeFailedException(e)
        }
        if (!committed) throw TokenWipeFailedException(IllegalStateException("token store commit returned false"))
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

    private val encryptedPrefs: SharedPreferences by encryptedPrefsHolder

    private fun tokenKey(profileId: String) = "token_$profileId"
}

// ── Device tokens (ADR-0007) ──────────────────────────────────────────────────

/**
 * [DeviceTokenStore] over the profile-token file, so [ConnectionProfileStore.clearAll] removes device tokens with
 * everything else. [ConnectionProfileStore] delegates to it and shares its single prefs instance.
 *
 * @param prefsHolder The token file, opened lazily (shared with [ConnectionProfileStore]).
 * @param ioDispatcher Dispatcher for the blocking EncryptedSharedPreferences I/O.
 */
private class EncryptedDeviceTokenStore(
    prefsHolder: Lazy<SharedPreferences>,
    private val ioDispatcher: CoroutineDispatcher,
) : DeviceTokenStore {

    private val prefs: SharedPreferences by prefsHolder

    override suspend fun loadDeviceToken(gatewayKey: String): StoredDeviceToken? = withContext(ioDispatcher) {
        // Keystore loss (typed in keystoreReadOrNull) reads as no token: the gateway re-issues one on the next
        // shared-token connect.
        var unreadable = false
        val raw = keystoreReadOrNull(onFailure = { unreadable = true }) {
            prefs.getString(deviceTokenKey(gatewayKey), null)
        }
        val decoded = raw?.let(::decodeDeviceToken)
        if (unreadable || (raw != null && decoded == null)) {
            // Best effort: when the file itself will not open, the removal fails too and the read stays null.
            keystoreReadOrNull { prefs.edit().remove(deviceTokenKey(gatewayKey)).apply() }
        }
        decoded
    }

    override suspend fun saveDeviceToken(gatewayKey: String, token: StoredDeviceToken): Unit =
        withContext(ioDispatcher) {
            val json = profileJson.encodeToString(StoredDeviceToken.serializer(), token)
            keystoreWrite("save the device token for ${gatewayForMessages(gatewayKey)}") {
                prefs.edit().putString(deviceTokenKey(gatewayKey), json).apply()
            }
        }

    override suspend fun clearDeviceToken(gatewayKey: String): Unit = withContext(ioDispatcher) {
        keystoreWrite("remove the device token for ${gatewayForMessages(gatewayKey)}") {
            prefs.edit().remove(deviceTokenKey(gatewayKey)).apply()
        }
    }
}

private fun deviceTokenKey(gatewayKey: String) = "device_token_$gatewayKey"

/** The key as a URL when it is one ([GatewayUrl] rejects userinfo); unparsed input may hold anything. */
private fun gatewayForMessages(gatewayKey: String): String = GatewayUrl.parse(gatewayKey)?.toString() ?: "this gateway"

/**
 * @param profiles The stored profiles before a delete.
 * @param profileId The profile being deleted.
 * @return The deleted profile's [gatewayKeyOf] when no other profile uses that gateway (its device token is then
 *   orphaned), else `null` (also for an unknown [profileId]).
 */
internal fun orphanedGatewayKey(profiles: List<StoredProfile>, profileId: String): String? {
    val key = profiles.firstOrNull { it.id == profileId }?.let { gatewayKeyOf(it.url) }
    return key?.takeIf { profiles.none { other -> other.id != profileId && gatewayKeyOf(other.url) == key } }
}

/** Decodes a stored device token; `null` for undecodable data (the caller removes the entry). */
internal fun decodeDeviceToken(json: String): StoredDeviceToken? = try {
    profileJson.decodeFromString(StoredDeviceToken.serializer(), json)
} catch (ignored: IllegalArgumentException) { // SerializationException extends it
    null
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
