package com.openclaw.ghostcrab.screenshots

import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openclaw.ghostcrab.MainActivity
import com.openclaw.ghostcrab.R
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64

/**
 * Google Play screenshots, captured on an emulator by `.github/workflows/android-screenshots.yml`.
 *
 * Walks onboarding → empty gateway picker → LAN scan → Manual Entry (pre-filled through a
 * `ghostcrab://pair` link) → dashboard → config editor → models → AI recommendations → settings →
 * picker with the saved gateway. The connected screens need the mock gateway
 * (`scripts/mock-gateway`) the workflow starts on the runner host; the capture script forwards the
 * emulator's `127.0.0.1:<port>` to it with `adb reverse`.
 *
 * Skipped unless the instrumentation argument `storeScreenshots=true` is set, so a plain
 * `connectedDebugAndroidTest` never depends on a gateway. Screens are captured with `screencap`
 * (status bar included) into [OUTPUT_DIR] on the device; the workflow pulls them. A screen that
 * does not appear is recorded and the walk continues; every miss fails the test at the end.
 *
 * Instrumentation arguments: `gatewayUrl` (default `http://127.0.0.1:18790`), `gatewayToken`
 * (default none) and `gatewayName` (default `Home Lab`).
 */
@RunWith(AndroidJUnit4::class)
class StoreScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val args = InstrumentationRegistry.getArguments()
    private val misses = mutableListOf<String>()

    @Test
    fun captureStoreScreenshots() {
        assumeTrue(
            "Store screenshots run only from android-screenshots.yml",
            args.getString(ARG_ENABLED) == "true",
        )
        shell("mkdir -p $OUTPUT_DIR")
        walk()
        assertTrue(misses.joinToString(separator = "\n"), misses.isEmpty())
    }

    private fun walk() {
        // 1. Onboarding: a first launch redirects the picker here.
        if (waitForText("Get Started")) snap("01-onboarding")
        clickText(string(R.string.onboarding_skip))

        // 2. Empty picker
        if (waitForText("Scan QR to connect")) snap("02-gateways-empty")

        // 3. LAN scan (nothing advertises on the emulator network: shows the active scan)
        if (clickText("Scan LAN")) {
            pause(SCAN_MS)
            snap("03-lan-scan")
            Espresso.pressBack()
        }

        // 4. Manual Entry, pre-filled by a ghostcrab://pair link (singleTop: onNewIntent)
        waitForText("Scan QR to connect")
        val activity = composeRule.activity
        activity.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(pairingLink())).setPackage(activity.packageName),
        )
        if (!waitForText("Connect to Gateway")) return
        pause(SETTLE_MS)
        snap("04-manual-entry")

        // 5. Dashboard
        clickText("Connect", scroll = true)
        val configure = string(R.string.dashboard_action_configure)
        if (!waitForText(configure, CONNECT_TIMEOUT_MS)) return
        pause(LOAD_MS)
        snap("05-dashboard")

        // 6. Config editor
        if (clickText(configure, scroll = true) && waitForText(string(R.string.config_editor_title))) {
            pause(LOAD_MS)
            snap("06-config-editor")
            Espresso.pressBack()
        }

        // 7. Models (the dashboard's "Models" card title is not clickable; the button is)
        if (waitForText(configure)) {
            val models = composeRule.onAllNodes(hasText(string(R.string.dashboard_action_models)) and hasClickAction())
            if (models.fetchSemanticsNodes().isEmpty()) {
                misses += "No Models button on the dashboard"
            } else {
                models.onLast().performScrollTo().performClick()
                pause(LOAD_MS)
                snap("07-models")
                Espresso.pressBack()
            }
        }

        // 8. AI recommendations
        if (waitForText(configure) && clickText(string(R.string.dashboard_action_ai_recommend), scroll = true)) {
            waitForText(string(R.string.ai_screen_title))
            pause(LOAD_MS)
            snap("08-ai-recommendation")
            Espresso.pressBack()
        }

        // 9. Settings (top-bar icon)
        if (waitForText(configure)) {
            click(hasContentDescription(string(R.string.dashboard_action_settings)) and hasClickAction(), "Settings")
            if (waitForText(string(R.string.settings_title))) {
                pause(SETTLE_MS)
                snap("09-settings")
                Espresso.pressBack()
            }
        }

        // 10. Picker with the saved gateway
        if (waitForText(configure) && clickText(string(R.string.dashboard_disconnect), scroll = true)) {
            if (waitForText(args.getString(ARG_NAME) ?: DEFAULT_NAME)) {
                pause(SETTLE_MS)
                snap("10-gateways-saved")
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private fun string(id: Int): String = composeRule.activity.getString(id)

    private fun exists(matcher: SemanticsMatcher): Boolean =
        composeRule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    /** Waits for a node showing exactly [text]; records a miss and returns `false` on timeout. */
    private fun waitForText(text: String, timeoutMs: Long = WAIT_TIMEOUT_MS): Boolean =
        try {
            composeRule.waitUntil(timeoutMs) { exists(hasText(text)) }
            true
        } catch (e: ComposeTimeoutException) {
            misses += "\"$text\" did not appear within ${timeoutMs / 1_000}s (${e.message})"
            // What was on screen instead; zz- names keep these apart from the store set.
            snap("zz-miss-${misses.size}")
            false
        }

    /**
     * Clicks the first clickable node showing exactly [text].
     *
     * @param scroll Scroll it into view first; only for nodes inside a scrollable container.
     * @return `false` (and a recorded miss) when no such node appeared.
     */
    private fun clickText(text: String, scroll: Boolean = false): Boolean {
        if (!waitForText(text)) return false
        return click(hasText(text) and hasClickAction(), "\"$text\"", scroll)
    }

    private fun click(matcher: SemanticsMatcher, what: String, scroll: Boolean = false): Boolean {
        val nodes = composeRule.onAllNodes(matcher)
        if (nodes.fetchSemanticsNodes().isEmpty()) {
            misses += "No clickable $what"
            return false
        }
        val node = nodes.onFirst()
        if (scroll) node.performScrollTo()
        node.performClick()
        return true
    }

    /** v1 `ghostcrab://pair?p=<base64url JSON>` link to the gateway named by the arguments. */
    private fun pairingLink(): String {
        val payload = JSONObject()
            .put("v", 1)
            .put("url", args.getString(ARG_URL) ?: DEFAULT_URL)
            .put("name", args.getString(ARG_NAME) ?: DEFAULT_NAME)
        args.getString(ARG_TOKEN)?.takeIf { it.isNotBlank() }?.let { payload.put("token", it) }
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toString().toByteArray(Charsets.UTF_8))
        return "ghostcrab://pair?p=$encoded"
    }

    /** Full-screen capture, status bar included, written to [OUTPUT_DIR] as `<name>.png`. */
    private fun snap(name: String) {
        composeRule.waitForIdle()
        pause(SNAP_SETTLE_MS)
        shell("screencap -p $OUTPUT_DIR/$name.png")
    }

    /** Runs [command] as the shell user and blocks until it exits. */
    private fun shell(command: String) {
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
    }

    private fun pause(ms: Long) = Thread.sleep(ms)

    private companion object {
        const val ARG_ENABLED = "storeScreenshots"
        const val ARG_URL = "gatewayUrl"
        const val ARG_TOKEN = "gatewayToken"
        const val ARG_NAME = "gatewayName"
        const val DEFAULT_URL = "http://127.0.0.1:18790"
        const val DEFAULT_NAME = "Home Lab"
        const val OUTPUT_DIR = "/data/local/tmp/ghostcrab-store-screenshots"
        const val WAIT_TIMEOUT_MS = 15_000L
        const val CONNECT_TIMEOUT_MS = 30_000L
        const val SCAN_MS = 3_000L
        const val LOAD_MS = 2_500L
        const val SETTLE_MS = 1_000L
        const val SNAP_SETTLE_MS = 500L
    }
}
