package com.openclaw.ghostcrab.data.storage

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.openclaw.ghostcrab.data.gateway.DeviceIdentity
import com.openclaw.ghostcrab.data.gateway.DeviceIdentityProvider
import com.openclaw.ghostcrab.domain.exception.DeviceCredentialStorageException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Base64

private const val IDENTITY_PREFS_FILE = "ghostcrab_device_identity"
private const val LOG_TAG = "DeviceIdentityStore"
private const val OPEN_OPERATION = "open the device identity"
private const val STORE_OPERATION = "store the device identity"

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
 * Recovery from Keystore loss: a file that will not open is retried once after [KEYSTORE_RETRY_DELAY_MS] (Keystore
 * errors are often transient), then deleted and recreated; an unreadable seed (cannot be decrypted, bad Base64, wrong
 * length) is removed. Either way a new identity is generated, [regenerated] turns `true` and a class-name-only
 * warning is logged: every gateway then shows a new pairing request for this phone. That is the only recovery a lost
 * key allows.
 *
 * Temporary seed copies are zeroed after use. Use one instance per process (a Koin `single`): the [Mutex] that
 * keeps two first-use calls from generating two identities is per instance.
 *
 * @param context Application context.
 * @param ioDispatcher Dispatcher for the blocking preferences I/O.
 * @param logWarning Receives the regeneration warning (never a secret or an exception message).
 * @param prefsFactory Opens the preferences; injectable for JVM tests.
 */
class DeviceIdentityStore(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val logWarning: (String) -> Unit = { Log.w(LOG_TAG, it) },
    private val prefsFactory: (Context) -> SharedPreferences = ::openIdentityPrefs,
) : DeviceIdentityProvider {

    private val mutex = Mutex()
    private val regeneratedState = MutableStateFlow(false)

    /** Set under [mutex] once opened; a failed open leaves it `null` so the next call tries again. */
    private var openedPrefs: SharedPreferences? = null

    @Volatile private var cached: DeviceIdentity? = null

    override val regenerated: StateFlow<Boolean> = regeneratedState.asStateFlow()

    /**
     * @return The persisted identity, generating and storing one on first use or after Keystore loss.
     * @throws DeviceCredentialStorageException if the identity file cannot be opened even after recreating it, or
     *   a new seed cannot be stored.
     */
    override suspend fun identity(): DeviceIdentity = cached ?: mutex.withLock {
        cached ?: withContext(ioDispatcher) { loadOrCreate() }.also { cached = it }
    }

    private suspend fun loadOrCreate(): DeviceIdentity {
        val prefs = openedPrefs ?: openWithRecovery().also { openedPrefs = it }
        val stored = readSeed(prefs)
        return if (stored != null) {
            try {
                DeviceIdentity.fromSeed(stored)
            } finally {
                stored.fill(0)
            }
        } else {
            DeviceIdentity.generate().also { persist(prefs, it) }
        }
    }

    private suspend fun openWithRecovery(): SharedPreferences {
        var failure: Exception? = null
        val opened = keystoreReadOrNull(onFailure = { failure = it }) { prefsFactory(context) }
            // One retry after a short pause: Keystore errors are often transient (e.g. right after boot).
            ?: run {
                delay(KEYSTORE_RETRY_DELAY_MS)
                keystoreReadOrNull(onFailure = { failure = it }) { prefsFactory(context) }
            }
        // prefsFactory never returns null, so a null here always carries its failure.
        return opened ?: recreate(checkNotNull(failure))
    }

    /** Deletes the identity file the Keystore can no longer open and opens a new, empty one. */
    private fun recreate(cause: Exception): SharedPreferences {
        reportRegenerated(cause::class.java.simpleName)
        context.deleteSharedPreferences(IDENTITY_PREFS_FILE)
        var failure: Exception = cause
        return keystoreReadOrNull(onFailure = { failure = it }) { prefsFactory(context) }
            ?: throw DeviceCredentialStorageException(OPEN_OPERATION, failure)
    }

    /**
     * The stored seed, or `null` when there is none or it is unusable. An unusable one (the Keystore cannot decrypt
     * it, bad Base64, wrong length) is removed with `commit()`, so it is gone before a new seed is generated.
     */
    @SuppressLint("ApplySharedPref", "UseKtx")
    private fun readSeed(prefs: SharedPreferences): ByteArray? {
        var unusable: String? = null
        val seed = keystoreReadOrNull(onFailure = { unusable = it::class.java.simpleName }) {
            prefs.getString(KEY_SEED, null)?.let { Base64.getDecoder().decode(it) }
        }
        if (seed != null && seed.size != DeviceIdentity.SEED_BYTES) {
            seed.fill(0)
            unusable = "seed of ${seed.size} bytes"
        }
        unusable?.let { reason ->
            reportRegenerated(reason)
            prefs.edit().remove(KEY_SEED).commit()
        }
        return seed?.takeIf { unusable == null }
    }

    // commit(), not apply() or KTX edit(): we need its synchronous result before handing out a new identity.
    @SuppressLint("ApplySharedPref", "UseKtx")
    private fun persist(prefs: SharedPreferences, identity: DeviceIdentity) {
        val seed = identity.seedForStorage()
        val encoded = try {
            Base64.getEncoder().encodeToString(seed)
        } finally {
            seed.fill(0)
        }
        var committed = false
        keystoreWrite(STORE_OPERATION) { committed = prefs.edit().putString(KEY_SEED, encoded).commit() }
        if (!committed) {
            throw DeviceCredentialStorageException(STORE_OPERATION, IllegalStateException("commit() returned false"))
        }
    }

    /** @param reason Exception class name or a description; never a message, seed or token. */
    private fun reportRegenerated(reason: String) {
        regeneratedState.value = true
        logWarning("Device identity unreadable ($reason); generating a new one. Gateways will ask to pair again.")
    }

    internal companion object {
        /** Preference key of the base64 seed. */
        const val KEY_SEED = "ed25519_seed_v1"

        /** Pause before the single retry of a failed open of the identity file. */
        const val KEYSTORE_RETRY_DELAY_MS = 250L
    }
}
