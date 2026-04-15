package com.openclaw.ghostcrab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.openclaw.ghostcrab.ui.navigation.NavGraph
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.GhostCrabTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GhostCrabTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = BrandTokens.colorAbyss,
                ) {
                    NavGraph()
                }
            }
        }
    }
}
