package com.openclaw.ghostcrab

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openclaw.ghostcrab.domain.util.PairCodeResult
import com.openclaw.ghostcrab.domain.util.decodePairCode
import com.openclaw.ghostcrab.ui.connection.PairingHandoff
import com.openclaw.ghostcrab.ui.navigation.NavGraph
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.GhostCrabTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.koin.android.ext.android.inject

/** Single activity. Also receives `ghostcrab://pair` deep links (`singleTop`: [onNewIntent] while running). */
class MainActivity : ComponentActivity() {

    private val pairingHandoff: PairingHandoff by inject()

    /** Incremented for every accepted `ghostcrab://pair` link; the nav graph navigates on change. */
    private val pairRequests = MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only on a fresh launch: after recreation the link was already handled.
        if (savedInstanceState == null) acceptPairIntent(intent)
        setContent {
            GhostCrabTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = BrandTokens.colorAbyss,
                ) {
                    val pairRequest by pairRequests.collectAsStateWithLifecycle()
                    NavGraph(pairRequest = pairRequest)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        acceptPairIntent(intent)
    }

    /**
     * Hands a `ghostcrab://pair` bundle to [PairingHandoff] (the token never enters a navigation
     * route) and asks the nav graph to open ManualEntry, which prefills but does not auto-connect.
     *
     * @param intent The launch or new intent; ignored when it carries no data URI.
     */
    private fun acceptPairIntent(intent: Intent?) {
        val data = intent?.dataString ?: return
        when (val result = decodePairCode(data)) {
            is PairCodeResult.Bundle -> {
                pairingHandoff.offer(result.bundle)
                pairRequests.update { it + 1 }
            }
            is PairCodeResult.Invalid -> Toast.makeText(this, result.reason, Toast.LENGTH_LONG).show()
            is PairCodeResult.PlainUrl -> Unit // the intent filter only matches ghostcrab://pair
        }
    }
}
