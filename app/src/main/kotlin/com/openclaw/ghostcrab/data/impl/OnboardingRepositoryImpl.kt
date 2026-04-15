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

private val Context.onboardingDataStore: DataStore<Preferences> by preferencesDataStore(
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
 * @param context Application context used to access the DataStore.
 */
public class OnboardingRepositoryImpl(
    private val context: Context,
) : OnboardingRepository {

    override suspend fun isCompleted(): Boolean =
        context.onboardingDataStore.data.firstOrNull()
            ?.get(KEY_COMPLETED) ?: false

    override suspend fun saveStep(step: OnboardingStep) {
        context.onboardingDataStore.edit { prefs ->
            prefs[KEY_CURRENT_STEP] = step.name
        }
    }

    override suspend fun getSavedStep(): OnboardingStep {
        val name = context.onboardingDataStore.data.firstOrNull()
            ?.get(KEY_CURRENT_STEP)
        return if (name != null) stepFromName(name) else OnboardingStep.Welcome
    }

    override suspend fun markCompleted() {
        context.onboardingDataStore.edit { prefs ->
            prefs[KEY_COMPLETED] = true
            prefs[KEY_CURRENT_STEP] = OnboardingStep.Completed.name
        }
    }

    override suspend fun reset() {
        context.onboardingDataStore.edit { prefs ->
            prefs.remove(KEY_COMPLETED)
            prefs.remove(KEY_CURRENT_STEP)
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/** Maps a stored step name back to an [OnboardingStep], defaulting to [OnboardingStep.Welcome]. */
private val OnboardingStep.name: String
    get() = when (this) {
        OnboardingStep.Welcome -> "Welcome"
        OnboardingStep.WhatIsOpenClaw -> "WhatIsOpenClaw"
        OnboardingStep.InstallGateway -> "InstallGateway"
        OnboardingStep.StartGateway -> "StartGateway"
        OnboardingStep.VerifyRunning -> "VerifyRunning"
        OnboardingStep.FindOnNetwork -> "FindOnNetwork"
        OnboardingStep.Completed -> "Completed"
    }

private fun stepFromName(name: String): OnboardingStep = when (name) {
    "WhatIsOpenClaw" -> OnboardingStep.WhatIsOpenClaw
    "InstallGateway" -> OnboardingStep.InstallGateway
    "StartGateway" -> OnboardingStep.StartGateway
    "VerifyRunning" -> OnboardingStep.VerifyRunning
    "FindOnNetwork" -> OnboardingStep.FindOnNetwork
    "Completed" -> OnboardingStep.Completed
    else -> OnboardingStep.Welcome
}
