package com.openclaw.ghostcrab.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
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
fun NavGraph() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "connection_picker",
    ) {
        composable("connection_picker") {
            ConnectionPickerScreen(
                onNavigateToManualEntry = { navController.navigate("manual_entry") },
                onNavigateToScan = { navController.navigate("scan") },
                onNavigateToQrScan = { navController.navigate("qr_scan") },
                onNavigateToDashboard = {
                    navController.navigate("dashboard") {
                        popUpTo("connection_picker") { inclusive = true }
                    }
                },
                onNavigateToOnboarding = {
                    navController.navigate("onboarding") {
                        popUpTo("connection_picker") { inclusive = false }
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
                onNavigateToDashboard = {
                    navController.navigate("dashboard") {
                        popUpTo("connection_picker") { inclusive = true }
                    }
                },
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
                onNavigateToDashboard = {
                    navController.navigate("dashboard") {
                        popUpTo("connection_picker") { inclusive = true }
                    }
                },
            )
        }

        composable("dashboard") {
            DashboardScreen(
                onNavigateBack = {
                    navController.navigate("connection_picker") {
                        popUpTo("connection_picker") { inclusive = true }
                    }
                },
                onNavigateToConfig = { navController.navigate("config_editor") },
                onNavigateToModels = { navController.navigate("model_manager") },
                onNavigateToAiRecommend = { navController.navigate("ai_recommendation") },
                onNavigateToSettings = { navController.navigate("settings") },
            )
        }

        composable("onboarding") {
            OnboardingScreen(
                onSkip = {
                    navController.navigate("connection_picker") {
                        popUpTo("onboarding") { inclusive = true }
                    }
                },
                onScan = {
                    navController.navigate("scan")
                },
                onManualEntry = {
                    navController.navigate("manual_entry")
                },
                onDone = {
                    navController.navigate("connection_picker") {
                        popUpTo("onboarding") { inclusive = true }
                    }
                },
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
        composable("ai_recommendation") {
            AIRecommendationScreen(onNavigateBack = { navController.popBackStack() })
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
