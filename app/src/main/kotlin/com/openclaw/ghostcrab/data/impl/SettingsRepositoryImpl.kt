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

private val Context.appSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app_settings",
)

private val KEY_ALLOW_CLEARTEXT_PUBLIC = booleanPreferencesKey("allow_cleartext_public_ips")

/**
 * DataStore-backed [SettingsRepository].
 *
 * All preferences default to `false` unless explicitly set.
 *
 * @param context Application context.
 */
class SettingsRepositoryImpl(private val context: Context) : SettingsRepository {

    override val allowCleartextPublicIPs: Flow<Boolean> =
        context.appSettingsDataStore.data.map { prefs ->
            prefs[KEY_ALLOW_CLEARTEXT_PUBLIC] ?: false
        }

    override suspend fun setAllowCleartextPublicIPs(enabled: Boolean) {
        context.appSettingsDataStore.edit { prefs ->
            prefs[KEY_ALLOW_CLEARTEXT_PUBLIC] = enabled
        }
    }
}
