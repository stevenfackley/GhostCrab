package com.openclaw.ghostcrab.ui.connection

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openclaw.ghostcrab.domain.model.AuthRequirement
import com.openclaw.ghostcrab.domain.model.ConnectionProfile
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import com.openclaw.ghostcrab.domain.model.OnboardingStep
import com.openclaw.ghostcrab.domain.repository.ConnectionProfileRepository
import com.openclaw.ghostcrab.domain.repository.GatewayConnectionManager
import com.openclaw.ghostcrab.domain.repository.OnboardingRepository
import com.openclaw.ghostcrab.ui.theme.GhostCrabTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI smoke tests for [ConnectionPickerScreen].
 *
 * Requires a device/emulator. Uses hand-rolled fakes (no Koin) so the test runs without
 * any DI wiring. Covers the empty-state hero, profile-list rendering, and the QR/scan
 * navigation callbacks.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionPickerScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun empty_state_shows_qr_hero_and_invokes_onScanQr_when_clicked() {
        val vm = buildVm(profiles = emptyList())
        var qrClicked = false

        composeRule.setContent {
            GhostCrabTheme {
                ConnectionPickerScreen(
                    onNavigateToManualEntry = {},
                    onNavigateToScan = {},
                    onNavigateToQrScan = { qrClicked = true },
                    onNavigateToDashboard = {},
                    viewModel = vm,
                )
            }
        }

        composeRule.onNodeWithText("Scan QR to connect").assertIsDisplayed()
        composeRule.onNodeWithText("Open Camera").assertIsDisplayed()
        composeRule.onNodeWithText("Scan LAN").assertIsDisplayed()
        composeRule.onNodeWithText("Manual").assertIsDisplayed()

        composeRule.onNodeWithText("Open Camera").performClick()
        assert(qrClicked) { "onNavigateToQrScan was not invoked" }
    }

    @Test
    fun profile_list_renders_each_profile_display_name_and_url() {
        val profile = ConnectionProfile(
            id = "abc",
            displayName = "living-room-gw",
            url = "http://10.0.0.5:18789",
            lastConnectedAt = null,
            hasToken = true,
        )
        val vm = buildVm(profiles = listOf(profile))

        composeRule.setContent {
            GhostCrabTheme {
                ConnectionPickerScreen(
                    onNavigateToManualEntry = {},
                    onNavigateToScan = {},
                    onNavigateToQrScan = {},
                    onNavigateToDashboard = {},
                    viewModel = vm,
                )
            }
        }

        composeRule.onNodeWithText("living-room-gw").assertIsDisplayed()
        composeRule.onNodeWithText("http://10.0.0.5:18789").assertIsDisplayed()
        composeRule.onNodeWithText("TOKEN AUTH").assertIsDisplayed()
    }

    @Test
    fun top_bar_qr_icon_invokes_onNavigateToQrScan() {
        val profile = ConnectionProfile(
            id = "x",
            displayName = "gw",
            url = "http://192.168.1.10:18789",
            lastConnectedAt = null,
            hasToken = false,
        )
        val vm = buildVm(profiles = listOf(profile))
        var qrClicked = false

        composeRule.setContent {
            GhostCrabTheme {
                ConnectionPickerScreen(
                    onNavigateToManualEntry = {},
                    onNavigateToScan = {},
                    onNavigateToQrScan = { qrClicked = true },
                    onNavigateToDashboard = {},
                    viewModel = vm,
                )
            }
        }

        composeRule.onNodeWithContentDescription("Scan QR Code").performClick()
        assert(qrClicked) { "top-bar QR icon did not trigger callback" }
    }

    // ── Fakes ────────────────────────────────────────────────────────────────

    private fun buildVm(
        profiles: List<ConnectionProfile>,
        onboardingCompleted: Boolean = true,
    ): ConnectionPickerViewModel = ConnectionPickerViewModel(
        profileRepository = FakeProfileRepo(profiles),
        connectionManager = FakeConnectionManager(),
        onboardingRepository = FakeOnboardingRepo(onboardingCompleted),
    )

    private class FakeProfileRepo(initial: List<ConnectionProfile>) : ConnectionProfileRepository {
        private val flow = MutableStateFlow(initial)
        override fun getProfiles(): Flow<List<ConnectionProfile>> = flow
        override suspend fun saveProfile(profile: ConnectionProfile, token: String?) = Unit
        override suspend fun getToken(profileId: String): String? = null
        override suspend fun deleteProfile(profileId: String) {
            flow.value = flow.value.filter { it.id != profileId }
        }
    }

    private class FakeConnectionManager : GatewayConnectionManager {
        private val state = MutableStateFlow<GatewayConnection>(GatewayConnection.Disconnected)
        override val connectionState: StateFlow<GatewayConnection> = state.asStateFlow()
        override suspend fun probeAuth(url: String): AuthRequirement = AuthRequirement.None
        override suspend fun connect(url: String, token: String?) = Unit
        override suspend fun disconnect() = Unit
    }

    private class FakeOnboardingRepo(private val completed: Boolean) : OnboardingRepository {
        override suspend fun isCompleted(): Boolean = completed
        override suspend fun saveStep(step: OnboardingStep) = Unit
        override suspend fun getSavedStep(): OnboardingStep = OnboardingStep.Welcome
        override suspend fun markCompleted() = Unit
        override suspend fun reset() = Unit
    }
}
