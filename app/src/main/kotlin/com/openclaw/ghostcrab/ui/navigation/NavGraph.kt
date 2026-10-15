package com.openclaw.ghostcrab.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.openclaw.ghostcrab.BuildConfig
import com.openclaw.ghostcrab.ui.airecommend.AIRecommendationScreen
import com.openclaw.ghostcrab.ui.config.ConfigEditorScreen
import com.openclaw.ghostcrab.ui.installedskills.InstalledSkillsScreen
import com.openclaw.ghostcrab.ui.settings.SettingsScreen
import com.openclaw.ghostcrab.ui.connection.ConnectionPickerScreen
import com.openclaw.ghostcrab.ui.connection.ManualEntryScreen
import com.openclaw.ghostcrab.ui.connection.QrScanScreen
import com.openclaw.ghostcrab.ui.connection.ScanScreen
import com.openclaw.ghostcrab.ui.dashboard.DashboardScreen
import com.openclaw.ghostcrab.ui.model.ModelManagerScreen
import com.openclaw.ghostcrab.ui.onboarding.OnboardingScreen

@Composable
fun NavGraph(pairRequest: Int = 0) {
    val navController = rememberNavController()

    // A `ghostcrab://pair` deep link was accepted (bundle waiting in PairingHandoff): open ManualEntry.
    LaunchedEffect(pairRequest) {
        if (pairRequest > 0) navController.navigate("manual_entry") { launchSingleTop = true }
    }

    NavHost(
        navController = navController,
        startDestination = "connection_picker",
    ) {
        composable("connection_picker") {
            ConnectionPickerScreen(
                onNavigateToManualEntry = { navController.navigate("manual_entry") },
                onNavigateToScan = { navController.navigate("scan") },
                onNavigateToQrScan = { navController.navigate("qr_scan") },
                onNavigateToDashboard = { navController.navigateAsOnlyEntry("dashboard") },
                onNavigateToOnboarding = {
                    // First-run redirect only while the picker is on top: if a deep link already pushed
                    // ManualEntry, popUpTo would pop the prefilled form.
                    if (navController.currentDestination?.route == "connection_picker") {
                        navController.navigate("onboarding") {
                            popUpTo("connection_picker") { inclusive = false }
                        }
                    }
                },
            )
        }

        composable("qr_scan") {
            QrScanScreen(
                onNavigateToManualEntry = { url ->
                    val encoded = android.net.Uri.encode(url)
                    navController.navigate("manual_entry?prefillUrl=$encoded") {
                        popUpTo("qr_scan") { inclusive = true }
                    }
                },
                onNavigateToPairedEntry = {
                    navController.navigate("manual_entry") {
                        popUpTo("qr_scan") { inclusive = true }
                    }
                },
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable(
            route = "manual_entry?prefillUrl={prefillUrl}",
            arguments = listOf(
                navArgument("prefillUrl") {
                    nullable = true
                    defaultValue = null
                    type = NavType.StringType
                },
            ),
        ) { backStackEntry ->
            val prefillUrl = backStackEntry.arguments?.getString("prefillUrl")
            ManualEntryScreen(
                prefillUrl = prefillUrl,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToDashboard = { navController.navigateAsOnlyEntry("dashboard") },
            )
        }

        composable("scan") {
            ScanScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToManualEntry = { url ->
                    val encoded = Uri.encode(url)
                    navController.navigate("manual_entry?prefillUrl=$encoded")
                },
                onEnterManually = {
                    navController.navigate("manual_entry")
                },
                onNavigateToDashboard = { navController.navigateAsOnlyEntry("dashboard") },
            )
        }

        composable("dashboard") {
            DashboardScreen(
                // The picker was popped when this dashboard opened, so popping up to it would keep this dashboard in
                // the stack; a resumed pairing wait would then push a second one, and both would poll.
                onNavigateBack = { navController.navigateAsOnlyEntry("connection_picker") },
                onNavigateToConfig = { navController.navigate("config_editor") },
                onNavigateToModels = { navController.navigate("model_manager") },
                onNavigateToAiRecommend = {
                    // The route exists only while the feature is on (ADR-0007: off until a new ADR).
                    if (BuildConfig.AI_PRO_ENABLED) navController.navigate("ai_recommendation")
                },
                onNavigateToSettings = { navController.navigate("settings") },
            )
        }

        composable("onboarding") {
            OnboardingScreen(
                // Onboarding opened over the start picker; popping only itself would stack a second picker on it.
                onSkip = { navController.navigateAsOnlyEntry("connection_picker") },
                onScan = {
                    navController.navigate("scan")
                },
                onManualEntry = {
                    navController.navigate("manual_entry")
                },
                onDone = { navController.navigateAsOnlyEntry("connection_picker") },
            )
        }

        composable("config_editor") {
            ConfigEditorScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }
        composable("model_manager") {
            ModelManagerScreen(onNavigateBack = { navController.popBackStack() })
        }
        if (BuildConfig.AI_PRO_ENABLED) {
            composable("ai_recommendation") {
                AIRecommendationScreen(onNavigateBack = { navController.popBackStack() })
            }
        }
        composable("settings") {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToInstalledSkills = {
                    if (BuildConfig.SKILLS_INSTALL_ENABLED) {
                        navController.navigate("installed_skills")
                    }
                },
            )
        }
        if (BuildConfig.SKILLS_INSTALL_ENABLED) {
            composable("installed_skills") {
                InstalledSkillsScreen(onNavigateBack = { navController.popBackStack() })
            }
        }
    }
}

/**
 * Opens [route] as the only back-stack entry. Every dashboard opening and the dashboard's way back to the picker use
 * it, so exactly one dashboard (one polling `DashboardViewModel`) lives in the back stack whichever screen opened it:
 * the picker, Manual Entry (also when a pairing link opened it over the dashboard), Scan, or a resumed pairing wait.
 * Onboarding's Skip / Done use it too, so the picker it was opened over is not left under a second one.
 */
private fun NavController.navigateAsOnlyEntry(route: String) {
    navigate(route) { popUpTo(graph.id) { inclusive = true } }
}
