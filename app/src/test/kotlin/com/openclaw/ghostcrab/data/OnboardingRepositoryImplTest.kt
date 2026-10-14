package com.openclaw.ghostcrab.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.openclaw.ghostcrab.data.impl.OnboardingRepositoryImpl
import com.openclaw.ghostcrab.domain.model.OnboardingStep
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OnboardingRepositoryImplTest {

    // In-memory store: DataStore's file store cannot replace its file on Windows after a read in tests.
    private fun newStore(): DataStore<Preferences> = object : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val next = transform(state.value)
            state.value = next
            return next
        }
    }

    @Test
    fun `fresh install starts at Welcome and is not completed`() = runTest {
        val repo = OnboardingRepositoryImpl(newStore())
        assertFalse(repo.isCompleted())
        assertEquals(OnboardingStep.Welcome, repo.getSavedStep())
    }

    @Test
    fun `saved step round-trips`() = runTest {
        val repo = OnboardingRepositoryImpl(newStore())
        repo.saveStep(OnboardingStep.VerifyRunning)
        assertEquals(OnboardingStep.VerifyRunning, repo.getSavedStep())
    }

    @Test
    fun `markCompleted then reset`() = runTest {
        val repo = OnboardingRepositoryImpl(newStore())

        repo.markCompleted()
        assertTrue(repo.isCompleted())
        assertEquals(OnboardingStep.Completed, repo.getSavedStep())

        repo.reset()
        assertFalse(repo.isCompleted())
        assertEquals(OnboardingStep.Welcome, repo.getSavedStep())
    }

    @Test
    fun `unknown stored step falls back to Welcome`() = runTest {
        val store = newStore()
        store.edit { it[stringPreferencesKey("current_step")] = "StepFromTheFuture" }
        assertEquals(OnboardingStep.Welcome, OnboardingRepositoryImpl(store).getSavedStep())
    }
}
