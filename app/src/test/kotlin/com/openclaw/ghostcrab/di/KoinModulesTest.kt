package com.openclaw.ghostcrab.di

import android.content.Context
import android.net.nsd.NsdManager
import android.net.wifi.WifiManager
import com.openclaw.ghostcrab.domain.repository.ConnectionProfileRepository
import com.openclaw.ghostcrab.domain.repository.GatewayConnectionManager
import com.openclaw.ghostcrab.domain.repository.GatewayHealthChecker
import com.openclaw.ghostcrab.domain.repository.SettingsRepository
import com.openclaw.ghostcrab.ui.airecommend.AIRecommendationViewModel
import com.openclaw.ghostcrab.ui.config.ConfigEditorViewModel
import com.openclaw.ghostcrab.ui.connection.ConnectionPickerViewModel
import com.openclaw.ghostcrab.ui.connection.ManualEntryViewModel
import com.openclaw.ghostcrab.ui.connection.QrScanViewModel
import com.openclaw.ghostcrab.ui.connection.ScanViewModel
import com.openclaw.ghostcrab.ui.dashboard.DashboardViewModel
import com.openclaw.ghostcrab.ui.model.ModelManagerViewModel
import com.openclaw.ghostcrab.ui.onboarding.OnboardingViewModel
import com.openclaw.ghostcrab.ui.settings.SettingsViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.test.verify.verify

@OptIn(KoinExperimentalAPI::class)
class KoinModulesTest {

    /**
     * Static check only: koin-test `verify()` inspects the public constructor parameter types of each
     * definition and requires a definition for each, except types listed in `extraTypes`. It skips
     * parameters with defaults and does not execute lambdas, so it cannot see a missing `single`
     * that is only used via `get(qualifier)` or a defaulted parameter; [real graph resolves] covers that.
     */
    @Test
    fun `app module constructor types are declared`() {
        appModule.verify(extraTypes = listOf(Context::class))
    }

    /** Builds an isolated Koin container (not the global one) and resolves ViewModels and key singles. */
    @Test
    fun `real graph resolves viewmodels and key singles`() {
        val context = mockk<Context>(relaxed = true) {
            every { applicationContext } returns this
            every { getSystemService(Context.NSD_SERVICE) } returns mockk<NsdManager>(relaxed = true)
            every { getSystemService(Context.WIFI_SERVICE) } returns mockk<WifiManager>(relaxed = true)
        }
        val contextModule = module { single<Context> { context } }
        val koin = koinApplication { modules(contextModule, appModule) }.koin
        try {
            listOf(
                ConnectionPickerViewModel::class, ManualEntryViewModel::class, ScanViewModel::class,
                DashboardViewModel::class, OnboardingViewModel::class, ConfigEditorViewModel::class,
                ModelManagerViewModel::class, AIRecommendationViewModel::class, SettingsViewModel::class,
                QrScanViewModel::class,
            ).forEach { assertNotNull(koin.get<Any>(it), "${it.simpleName} did not resolve") }

            assertNotNull(koin.get<GatewayConnectionManager>())
            assertNotNull(koin.get<GatewayHealthChecker>())
            assertNotNull(koin.get<ConnectionProfileRepository>())
            assertNotNull(koin.get<SettingsRepository>())
            assertEquals(Dispatchers.IO, koin.get<CoroutineDispatcher>(IoDispatcher))
        } finally {
            koin.close()
        }
    }
}
