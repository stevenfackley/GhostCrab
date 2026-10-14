package com.openclaw.ghostcrab.di

import android.content.Context
import androidx.datastore.core.DataStore
import org.junit.jupiter.api.Test
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Static check of the Koin graph: every constructor dependency of every definition must itself be
 * declared. `extraTypes` lists what definitions pass explicitly instead of resolving with `get()`
 * (the Android Context, DataStores, lambdas, defaulted constructor parameters).
 */
@OptIn(KoinExperimentalAPI::class)
class KoinModulesTest {

    @Test
    fun `app module graph resolves`() {
        appModule.verify(
            extraTypes = listOf(
                Context::class,
                DataStore::class,
                Function0::class,
                Function1::class,
            ),
        )
    }
}
