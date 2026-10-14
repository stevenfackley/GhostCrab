package com.openclaw.ghostcrab.data.impl

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.openclaw.ghostcrab.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** App-settings store (file `app_settings`), provided to DI. */
internal val Context.appSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app_settings",
)

private val KEY_ALLOW_CLEARTEXT_PUBLIC = booleanPreferencesKey("allow_cleartext_public_ips")

/**
 * DataStore-backed [SettingsRepository].
 *
 * All preferences default to `false` unless explicitly set.
 *
 * @param dataStore The `app_settings` preferences store.
 */
class SettingsRepositoryImpl(private val dataStore: DataStore<Preferences>) : SettingsRepository {

    override val allowCleartextPublicIPs: Flow<Boolean> =
        dataStore.data.map { prefs ->
            prefs[KEY_ALLOW_CLEARTEXT_PUBLIC] ?: false
        }

    override suspend fun setAllowCleartextPublicIPs(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_ALLOW_CLEARTEXT_PUBLIC] = enabled
        }
    }
}
