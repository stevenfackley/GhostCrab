package com.openclaw.ghostcrab.ui.onboarding

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.openclaw.ghostcrab.R
import com.openclaw.ghostcrab.domain.model.OnboardingStep
import com.openclaw.ghostcrab.ui.onboarding.steps.ConnectedStep
import com.openclaw.ghostcrab.ui.onboarding.steps.FindOnNetworkStep
import com.openclaw.ghostcrab.ui.onboarding.steps.InstallGatewayStep
import com.openclaw.ghostcrab.ui.onboarding.steps.StartGatewayStep
import com.openclaw.ghostcrab.ui.onboarding.steps.VerifyRunningStep
import com.openclaw.ghostcrab.ui.onboarding.steps.WelcomeStep
import com.openclaw.ghostcrab.ui.onboarding.steps.WhatIsOpenClawStep
import org.koin.androidx.compose.koinViewModel

/**
 * Host composable for the onboarding walkthrough.
 *
 * Observes [OnboardingViewModel.step] and renders the appropriate step composable
 * inside [OnboardingScaffold]. When the step reaches [OnboardingStep.Completed],
 * [onDone] is called exactly once.
 *
 * @param onSkip Called when the user skips onboarding; should navigate to connection picker.
 * @param onScan Called from [FindOnNetworkStep] to navigate to the LAN scan screen.
 * @param onManualEntry Called from [FindOnNetworkStep] to navigate to manual URL entry.
 * @param onDone Called when onboarding completes; should navigate to connection picker or dashboard.
 * @param viewModel Injected via Koin by default.
 */
@Composable
public fun OnboardingScreen(
    onSkip: () -> Unit,
    onScan: () -> Unit,
    onManualEntry: () -> Unit,
    onDone: () -> Unit,
    viewModel: OnboardingViewModel = koinViewModel(),
) {
    val step by viewModel.step.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(step) {
        if (step == OnboardingStep.Completed) onDone()
    }

    when (step) {
        OnboardingStep.Welcome -> OnboardingScaffold(
            step = step,
            onBack = null,
            onSkip = { viewModel.skip() },
            title = stringResource(R.string.onboarding_welcome_title),
        ) {
            WelcomeStep(
                onNext = { viewModel.next() },
                onSkip = { viewModel.skip() },
            )
        }

        OnboardingStep.WhatIsOpenClaw -> OnboardingScaffold(
            step = step,
            onBack = { viewModel.back() },
            onSkip = { viewModel.skip() },
            title = stringResource(R.string.onboarding_what_title),
        ) {
            WhatIsOpenClawStep(onNext = { viewModel.next() })
        }

        OnboardingStep.InstallGateway -> OnboardingScaffold(
            step = step,
            onBack = { viewModel.back() },
            onSkip = { viewModel.skip() },
            title = stringResource(R.string.onboarding_install_title),
        ) {
            InstallGatewayStep(
                snackbarHostState = snackbarHostState,
                onNext = { viewModel.next() },
            )
        }

        OnboardingStep.StartGateway -> OnboardingScaffold(
            step = step,
            onBack = { viewModel.back() },
            onSkip = { viewModel.skip() },
            title = stringResource(R.string.onboarding_start_title),
        ) {
            StartGatewayStep(onNext = { viewModel.next() })
        }

        OnboardingStep.VerifyRunning -> OnboardingScaffold(
            step = step,
            onBack = { viewModel.back() },
            onSkip = { viewModel.skip() },
            title = stringResource(R.string.onboarding_verify_title),
        ) {
            VerifyRunningStep(onNext = { viewModel.next() })
        }

        OnboardingStep.FindOnNetwork -> OnboardingScaffold(
            step = step,
            onBack = { viewModel.back() },
            onSkip = { viewModel.skip() },
            title = stringResource(R.string.onboarding_find_title),
        ) {
            FindOnNetworkStep(
                onScan = onScan,
                onManualEntry = onManualEntry,
            )
        }

        OnboardingStep.Completed -> ConnectedStep(onDone = onDone)
    }
}
