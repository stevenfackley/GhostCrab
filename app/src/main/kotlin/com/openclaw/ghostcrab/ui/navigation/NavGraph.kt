package com.openclaw.ghostcrab.ui.navigation

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.openclaw.ghostcrab.ui.connection.ConnectionPickerScreen
import com.openclaw.ghostcrab.ui.connection.ManualEntryScreen
import com.openclaw.ghostcrab.ui.connection.ScanScreen
import com.openclaw.ghostcrab.ui.dashboard.DashboardScreen

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
                onNavigateToDashboard = {
                    navController.navigate("dashboard") {
                        popUpTo("connection_picker") { inclusive = true }
                    }
                },
            )
        }

        composable(
            route = "manual_entry?prefillUrl={prefillUrl}",
            arguments = listOf(
                navArgument("prefillUrl") {
                    nullable = true
                    defaultValue = null
                    type = NavType.StringType
                }
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
                    navController.navigate("manual_entry?prefillUrl=${Uri.encode(url)}")
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
            )
        }
        composable("onboarding") { Placeholder("Onboarding\n(Phase 5)") }
        composable("config_editor") { Placeholder("Config Editor\n(Phase 6)") }
        composable("model_manager") { Placeholder("Model Manager\n(Phase 7)") }
        composable("ai_recommendation") { Placeholder("AI Recommendations\n(Phase 8)") }
        composable("settings") { Placeholder("Settings\n(Phase 9)") }
    }
}

@Composable
private fun Placeholder(label: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = label)
    }
}
