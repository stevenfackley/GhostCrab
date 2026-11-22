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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OnboardingRepositoryImplTest {

    // In-memory store: DataStore's file store cannot replace its file on Windows after a read in tests.
    private fun newStore(): DataStore<Preferences> = object : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            return mutex.withLock {
                transform(state.value).also { state.value = it }
            }
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

    @Test
    fun `every step is stored under its persisted name and reads back unchanged`() = runTest {
        assertEquals(OnboardingStep.entries.toList(), PERSISTED_NAMES.map { it.first })
        PERSISTED_NAMES.forEach { (step, name) ->
            val store = newStore()
            val repo = OnboardingRepositoryImpl(store)

            repo.saveStep(step)

            assertEquals(name, store.data.first()[CURRENT_STEP], "stored name of $step")
            assertEquals(step, repo.getSavedStep(), "read back of $name")
        }
    }

    @Test
    fun `a stored name written by an earlier install reads back as that step`() = runTest {
        PERSISTED_NAMES.forEach { (step, name) ->
            val store = newStore()
            store.edit { it[CURRENT_STEP] = name }
            assertEquals(step, OnboardingRepositoryImpl(store).getSavedStep(), "read of $name")
        }
    }

    @Test
    fun `stored names are matched case-sensitively`() = runTest {
        val store = newStore()
        store.edit { it[CURRENT_STEP] = "verifyrunning" }
        assertEquals(OnboardingStep.Welcome, OnboardingRepositoryImpl(store).getSavedStep())
    }

    private companion object {
        val CURRENT_STEP = stringPreferencesKey("current_step")

        /** The strings already on users' devices: changing one strands that user's progress. */
        val PERSISTED_NAMES = listOf(
            OnboardingStep.Welcome to "Welcome",
            OnboardingStep.WhatIsOpenClaw to "WhatIsOpenClaw",
            OnboardingStep.InstallGateway to "InstallGateway",
            OnboardingStep.StartGateway to "StartGateway",
            OnboardingStep.VerifyRunning to "VerifyRunning",
            OnboardingStep.FindOnNetwork to "FindOnNetwork",
            OnboardingStep.Completed to "Completed",
        )
    }
}
