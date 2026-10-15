package com.openclaw.ghostcrab.data.storage

import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Runs [read] against EncryptedSharedPreferences and maps the failures a lost or reset Keystore key and a corrupt
 * value produce to `null`: [GeneralSecurityException] (key or keyset unusable), [SecurityException] (the prefs'
 * wrapper for a failed decrypt), [IOException] (unreadable keyset) and [IllegalArgumentException] (bad Base64 /
 * JSON). Anything else is a bug and propagates.
 *
 * @param read The read; must not have side effects that a retry would repeat.
 * @return The value, or `null` when it is unreadable.
 */
internal inline fun <T> keystoreReadOrNull(read: () -> T?): T? = try {
    read()
} catch (_: GeneralSecurityException) {
    null
} catch (_: SecurityException) {
    null
} catch (_: IOException) {
    null
} catch (_: IllegalArgumentException) {
    null
}
