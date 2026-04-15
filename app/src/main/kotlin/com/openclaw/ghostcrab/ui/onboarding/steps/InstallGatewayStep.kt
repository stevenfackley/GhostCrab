package com.openclaw.ghostcrab.ui.onboarding.steps

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.openclaw.ghostcrab.R
import com.openclaw.ghostcrab.ui.components.CodeBlock
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.Spacing
import kotlinx.coroutines.launch

private val INSTALL_TABS = listOf("Linux/macOS", "Windows", "Raspberry Pi", "Docker")

private val INSTALL_COMMANDS = listOf(
    "curl -fsSL https://openclaw.ai/install.sh | sh",
    "irm https://openclaw.ai/install.ps1 | iex",
    "curl -fsSL https://openclaw.ai/install-rpi.sh | sh",
    "docker run -p 18789:18789 openclaw/gateway:latest",
)

/**
 * Onboarding step showing platform-specific install commands.
 *
 * @param snackbarHostState Used to display a "Copied" toast.
 * @param onNext Called when the user taps "Next".
 */
@Composable
public fun InstallGatewayStep(
    snackbarHostState: SnackbarHostState,
    onNext: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }
    var copyEvent by remember { mutableStateOf(false) }

    val copyToast = stringResource(R.string.onboarding_install_copy_toast)

    LaunchedEffect(copyEvent) {
        if (copyEvent) {
            scope.launch { snackbarHostState.showSnackbar(copyToast) }
            copyEvent = false
        }
    }

    Column {
        ScrollableTabRow(
            selectedTabIndex = selectedTab,
            containerColor = BrandTokens.colorAbyss,
            contentColor = BrandTokens.colorCyanPrimary,
            edgePadding = Spacing.xs,
        ) {
            INSTALL_TABS.forEachIndexed { index, label ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            text = label,
                            color = if (selectedTab == index) BrandTokens.colorCyanPrimary
                            else BrandTokens.colorTextSecondary,
                        )
                    },
                )
            }
        }

        Spacer(Modifier.height(Spacing.md))

        CodeBlock(
            code = INSTALL_COMMANDS[selectedTab],
            onCopied = { copyEvent = true },
        )

        Spacer(Modifier.height(Spacing.sm))

        TextButton(
            onClick = {
                val intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://openclaw.ai/docs/install"),
                )
                context.startActivity(intent)
            },
        ) {
            Text(
                text = stringResource(R.string.onboarding_install_docs_link),
                style = MaterialTheme.typography.bodySmall,
                color = BrandTokens.colorCyanPulse,
            )
        }

        Spacer(Modifier.height(Spacing.md))

        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = BrandTokens.colorCyanPrimary,
                contentColor = BrandTokens.colorAbyss,
            ),
        ) {
            Text(text = stringResource(R.string.onboarding_next))
        }
    }
}
