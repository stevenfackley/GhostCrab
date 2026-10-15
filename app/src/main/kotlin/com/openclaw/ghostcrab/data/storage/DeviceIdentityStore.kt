package com.openclaw.ghostcrab.data.storage

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.openclaw.ghostcrab.data.gateway.DeviceIdentity
import com.openclaw.ghostcrab.data.gateway.DeviceIdentityProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Base64

private const val IDENTITY_PREFS_FILE = "ghostcrab_device_identity"

/** Opens the EncryptedSharedPreferences file holding the device seed (AES256-GCM values, Keystore-wrapped key). */
internal fun openIdentityPrefs(context: Context): SharedPreferences {
    val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    return EncryptedSharedPreferences.create(
        context,
        IDENTITY_PREFS_FILE,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
}

/**
 * Persists this install's Ed25519 seed (ADR-0007) in its own encrypted file, separate from the token file, so
 * "Clear all saved gateways" keeps the identity and the gateways' pairing records stay valid.
 *
 * An unreadable seed (Keystore reset, corrupt value) is removed and a new identity is generated; the gateway
 * then shows a new pairing request for this phone. That is the only recovery a lost key allows.
 *
 * @param context Application context.
 * @param ioDispatcher Dispatcher for the blocking preferences I/O.
 * @param prefsFactory Opens the preferences; injectable for JVM tests.
 */
class DeviceIdentityStore(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val prefsFactory: (Context) -> SharedPreferences = ::openIdentityPrefs,
) : DeviceIdentityProvider {

    private val mutex = Mutex()
    private val prefs: SharedPreferences by lazy { prefsFactory(context) }

    @Volatile private var cached: DeviceIdentity? = null

    /**
     * @return The persisted identity, generating and storing one on first use.
     * @throws IllegalStateException if a newly generated seed cannot be committed.
     */
    override suspend fun identity(): DeviceIdentity = cached ?: mutex.withLock {
        cached ?: withContext(ioDispatcher) { loadOrCreate() }.also { cached = it }
    }

    // commit(), not apply() or KTX edit(): we need its synchronous result before handing out a new identity.
    @SuppressLint("ApplySharedPref", "UseKtx")
    private fun loadOrCreate(): DeviceIdentity {
        readSeed()?.let { return DeviceIdentity.fromSeed(it) }
        val fresh = DeviceIdentity.generate()
        val encoded = Base64.getEncoder().encodeToString(fresh.seedForStorage())
        check(prefs.edit().putString(KEY_SEED, encoded).commit()) { "device identity could not be persisted" }
        return fresh
    }

    /**
     * The stored seed, or `null` when absent or unusable (an unusable entry is removed first, with `commit()`
     * so it is gone synchronously before a new seed is generated; same call style as [loadOrCreate]).
     */
    @SuppressLint("ApplySharedPref", "UseKtx")
    private fun readSeed(): ByteArray? {
        // Keystore loss or a corrupt value: recovery is a new identity (the gateway then shows a new pairing request).
        val seed = keystoreReadOrNull { prefs.getString(KEY_SEED, null)?.let { Base64.getDecoder().decode(it) } }
        if (seed?.size == DeviceIdentity.SEED_BYTES) return seed
        prefs.edit().remove(KEY_SEED).commit()
        return null
    }

    internal companion object {
        /** Preference key of the base64 seed. */
        const val KEY_SEED = "ed25519_seed_v1"
    }
}
