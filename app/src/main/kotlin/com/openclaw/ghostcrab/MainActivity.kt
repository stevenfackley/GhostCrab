package com.openclaw.ghostcrab

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
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
        // The app is dark-only: always light system-bar icons. The default (SystemBarStyle.auto)
        // follows the system theme and drew dark icons on the dark background in light mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
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
     * @param intent The launch or new intent; ignored unless [pairLinkToHandle] accepts it.
     */
    private fun acceptPairIntent(intent: Intent?) {
        val link = intent?.let { pairLinkToHandle(it.action, it.scheme, it.flags, it.dataString) } ?: return
        when (val result = decodePairCode(link)) {
            is PairCodeResult.Bundle -> {
                pairingHandoff.offer(result.bundle)
                pairRequests.update { it + 1 }
            }
            is PairCodeResult.Invalid ->
                Toast.makeText(this, getString(R.string.pair_link_invalid, result.reason), Toast.LENGTH_LONG).show()
            is PairCodeResult.PlainUrl -> Unit // unreachable: pairLinkToHandle only passes ghostcrab: links
            is PairCodeResult.SetupCode -> Unit // unreachable: pairLinkToHandle only passes ghostcrab: links
        }
    }
}

private const val PAIR_LINK_SCHEME = "ghostcrab"

/**
 * Picks the pairing link out of an incoming intent's fields.
 *
 * @param action Intent action.
 * @param scheme Scheme of the intent's data URI.
 * @param flags Intent flags.
 * @param dataString The data URI as a string.
 * @return [dataString] for an `ACTION_VIEW` of the `ghostcrab` scheme; `null` for anything else, and for a
 *   relaunch from Recents, which re-delivers the original (already handled) link with its token.
 */
internal fun pairLinkToHandle(action: String?, scheme: String?, flags: Int, dataString: String?): String? =
    dataString.takeIf {
        action == Intent.ACTION_VIEW &&
            PAIR_LINK_SCHEME.equals(scheme, ignoreCase = true) &&
            flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0
    }
