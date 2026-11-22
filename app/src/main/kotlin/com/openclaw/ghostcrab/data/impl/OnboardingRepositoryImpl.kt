package com.openclaw.ghostcrab.data.impl

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.openclaw.ghostcrab.domain.model.OnboardingStep
import com.openclaw.ghostcrab.domain.repository.OnboardingRepository
import kotlinx.coroutines.flow.firstOrNull

/** Onboarding store (file `onboarding`), provided to DI. */
internal val Context.onboardingDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "onboarding",
)

private val KEY_CURRENT_STEP = stringPreferencesKey("current_step")
private val KEY_COMPLETED = booleanPreferencesKey("completed")

/**
 * DataStore-backed implementation of [OnboardingRepository].
 *
 * Uses a dedicated `"onboarding"` DataStore file to avoid collisions with
 * [com.openclaw.ghostcrab.data.storage.ConnectionProfileStore].
 *
 * @param dataStore The `onboarding` preferences store.
 */
public class OnboardingRepositoryImpl(
    private val dataStore: DataStore<Preferences>,
) : OnboardingRepository {

    override suspend fun isCompleted(): Boolean =
        dataStore.data.firstOrNull()
            ?.get(KEY_COMPLETED) ?: false

    override suspend fun saveStep(step: OnboardingStep) {
        dataStore.edit { prefs ->
            prefs[KEY_CURRENT_STEP] = step.name
        }
    }

    override suspend fun getSavedStep(): OnboardingStep {
        val name = dataStore.data.firstOrNull()
            ?.get(KEY_CURRENT_STEP)
        return if (name != null) stepFromName(name) else OnboardingStep.Welcome
    }

    override suspend fun markCompleted() {
        dataStore.edit { prefs ->
            prefs[KEY_COMPLETED] = true
            prefs[KEY_CURRENT_STEP] = OnboardingStep.Completed.name
        }
    }

    override suspend fun reset() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_COMPLETED)
            prefs.remove(KEY_CURRENT_STEP)
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/**
 * Maps a stored step name (the entry's [OnboardingStep.name]) back to an [OnboardingStep].
 * An unknown name means DataStore has a future/corrupted value: restart from
 * [OnboardingStep.Welcome] rather than crash.
 */
private fun stepFromName(name: String): OnboardingStep =
    OnboardingStep.entries.firstOrNull { it.name == name } ?: OnboardingStep.Welcome
