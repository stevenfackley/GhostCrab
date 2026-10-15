package com.openclaw.ghostcrab.data.storage

import com.openclaw.ghostcrab.domain.exception.DeviceCredentialStorageException
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException

/**
 * Runs [read] against EncryptedSharedPreferences and maps the failures a lost or reset Keystore key and a corrupt
 * value produce to `null`: [GeneralSecurityException] (key or keyset unusable), [SecurityException] (the prefs'
 * wrapper for a failed decrypt), [IOException] (unreadable keyset), [ProviderException] (the Android Keystore
 * provider failing) and [IllegalArgumentException] (bad Base64 / JSON). Anything else is a bug and propagates.
 *
 * @param onFailure Receives the mapped failure (for a class-name-only log line or a typed rethrow).
 * @param read The read; must not have side effects that a retry would repeat.
 * @return The value, or `null` when it is unreadable.
 */
internal inline fun <T> keystoreReadOrNull(onFailure: (Exception) -> Unit = {}, read: () -> T?): T? = try {
    read()
} catch (e: GeneralSecurityException) {
    onFailure(e)
    null
} catch (e: SecurityException) {
    onFailure(e)
    null
} catch (e: IOException) {
    onFailure(e)
    null
} catch (e: ProviderException) {
    onFailure(e)
    null
} catch (e: IllegalArgumentException) {
    onFailure(e)
    null
}

/**
 * Runs [write] against EncryptedSharedPreferences, turning the failures [keystoreReadOrNull] lists into a typed
 * [DeviceCredentialStorageException].
 *
 * @param operation What [write] does, for the error message (e.g. `save the device token for <gateway>`). No secrets.
 * @param write The write.
 * @throws DeviceCredentialStorageException if the Keystore or the encrypted file refuses it.
 */
internal inline fun keystoreWrite(operation: String, write: () -> Unit) {
    var failure: Exception? = null
    keystoreReadOrNull(onFailure = { failure = it }) { write() }
    failure?.let { throw DeviceCredentialStorageException(operation, it) }
}
