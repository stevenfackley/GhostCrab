import SwiftUI

/// Top-level navigation container. Picks the start destination based on
/// onboarding state, then dispatches every named ``Route`` to its
/// corresponding screen.
///
/// Every VM-backed screen goes through ``LazyViewModelHost`` so its VM is
/// built once per destination, not once per body evaluation.
///
/// On iPad and Mac Catalyst the content is width-capped so long lines stay
/// readable in landscape. The Android single-pane layout maps to a
/// max-width-constrained iOS single stack rather than `NavigationSplitView`
/// in v1.
struct RootView: View {

    @Environment(\.appContainer) private var container
    @State private var navigator = Navigator()
    @State private var deepLinkError: String?

    var body: some View {
        NavigationStack(path: $navigator.path) {
            startGate
                .navigationDestination(for: Route.self) { destination(for: $0) }
        }
        // Set on the whole stack so pushed destinations get it too. Set inside
        // `startGate` it reached only the start screen, and every pushed screen
        // (manual entry → dashboard, AI → installed skills) got the no-op default.
        .environment(\.navigate, NavigateAction(navigator))
        .task { await resolveStartRoute() }
        .onOpenURL { handleOpenURL($0) }
        .alert(
            "Pairing link rejected",
            isPresented: Binding(
                get: { deepLinkError != nil },
                set: { if !$0 { deepLinkError = nil } }
            ),
            presenting: deepLinkError
        ) { _ in
            Button("OK", role: .cancel) { deepLinkError = nil }
        } message: { message in
            Text(message)
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: - Start gate

    @ViewBuilder
    private var startGate: some View {
        if let root = navigator.root {
            destination(for: root)
        } else {
            // Pre-resolution splash: solid abyss + spinner. Brief; resolves on first frame.
            DesignTokens.Color.abyss
                .overlay {
                    ProgressView()
                        .tint(DesignTokens.Color.cyanPulse)
                }
                .ignoresSafeArea()
        }
    }

    private func resolveStartRoute() async {
        let completed = await container.onboarding.isCompleted()
        navigator.resolveRoot(completed ? .connectionPicker : .onboarding)
    }

    // MARK: - Deep links

    /// Handles `ghostcrab://pair?p=…` from the OS camera, Safari or another app.
    /// Other schemes are ignored.
    ///
    /// The token goes to the container's ``PairingHandoff``; only the gateway URL
    /// enters the navigation path, and a repeated delivery of the same link is
    /// dropped (see ``PairingDeepLink/open(_:handoff:navigator:)``). Nothing
    /// connects until the user taps Connect, so a hostile link can at most
    /// pre-fill a form the user can read and reject.
    /// The alert never echoes the payload (it contains the token).
    private func handleOpenURL(_ url: URL) {
        do {
            try PairingDeepLink.open(url, handoff: container.pairingHandoff, navigator: navigator)
        } catch {
            deepLinkError = PairingDeepLink.rejectionMessage(for: url, error: error)
        }
    }

    // MARK: - Route dispatch

    @ViewBuilder
    private func destination(for route: Route) -> some View {
        switch route {
        case .onboarding:
            LazyViewModelHost(make: { container.makeOnboardingVM() }) { OnboardingScreen(vm: $0) }
        case .connectionPicker:
            LazyViewModelHost(make: { container.makeConnectionPickerVM() }) { ConnectionPickerScreen(vm: $0) }
        case .manualEntry(let prefillURL):
            LazyViewModelHost(make: { container.makeManualEntryVM() }) {
                ManualEntryScreen(vm: $0, prefillURL: prefillURL)
            }
        case .qrScan:
            QrScanScreen()
        case .scan:
            LazyViewModelHost(make: { container.makeScanVM() }) { ScanScreen(vm: $0) }
        case .dashboard:
            LazyViewModelHost(make: { container.makeDashboardVM() }) { DashboardScreen(vm: $0) }
        case .configEditor:
            LazyViewModelHost(make: { container.makeConfigEditorVM() }) { ConfigEditorScreen(viewModel: $0) }
        case .modelManager:
            LazyViewModelHost(make: { container.makeModelManagerVM() }) { ModelManagerScreen(vm: $0) }
        case .aiRecommendation:
            AIRecommendationScreen()
        case .installedSkills:
            LazyViewModelHost(make: { container.makeInstalledSkillsVM() }) { InstalledSkillsScreen(viewModel: $0) }
        case .settings:
            SettingsScreen()
        }
    }
}
