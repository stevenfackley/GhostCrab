package com.openclaw.ghostcrab.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.openclaw.ghostcrab.data.impl.SettingsRepositoryImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SettingsRepositoryImplTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `cleartext opt-in defaults to false and persists when set`() = runTest {
        val store = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { File(tempDir, "app_settings.preferences_pb") },
        )
        val repo = SettingsRepositoryImpl(store)

        assertFalse(repo.allowCleartextPublicIPs.first())
        repo.setAllowCleartextPublicIPs(true)
        assertTrue(repo.allowCleartextPublicIPs.first())
    }
}
