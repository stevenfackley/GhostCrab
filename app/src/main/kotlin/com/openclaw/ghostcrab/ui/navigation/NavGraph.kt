package com.openclaw.ghostcrab.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

/**
 * Root navigation graph.
 *
 * Each destination is a placeholder until the corresponding phase implements its screen.
 * Start destination is [Routes.ConnectionPicker]; onboarding (Phase 5) intercepts on first launch.
 */
@Composable
fun NavGraph() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "connection_picker",
    ) {
        composable("connection_picker") { Placeholder("Connection Picker\n(Phase 2)") }
        composable("manual_entry") { Placeholder("Manual Entry\n(Phase 2)") }
        composable("scan") { Placeholder("LAN Scan\n(Phase 3)") }
        composable("dashboard") { Placeholder("Dashboard\n(Phase 4)") }
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
