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
    @State private var path = NavigationPath()
    @State private var startRoute: Route?
    @State private var deepLinkError: String?

    var body: some View {
        NavigationStack(path: $path) {
            startGate
                .navigationDestination(for: Route.self) { destination(for: $0) }
        }
        // Set on the whole stack so pushed destinations get it too. Set inside
        // `startGate` it reached only the start screen, and every pushed screen
        // (manual entry → dashboard, AI → installed skills) got the no-op default.
        .environment(\.navigate, NavigateAction { route in path.append(route) })
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
        if let startRoute {
            destination(for: startRoute)
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
        startRoute = completed ? .connectionPicker : .onboarding
    }

    // MARK: - Deep links

    /// Handles `ghostcrab://pair?p=…` from the OS camera, Safari or another app.
    /// Other schemes are ignored.
    ///
    /// The token goes to the container's ``PairingHandoff``; only the gateway URL
    /// enters the navigation path. Nothing connects until the user taps Connect,
    /// so a hostile link can at most pre-fill a form the user can read and reject.
    /// The alert never echoes the payload (it contains the token).
    private func handleOpenURL(_ url: URL) {
        do {
            if let route = try PairingDeepLink.route(for: url, handoff: container.pairingHandoff) {
                path.append(route)
            }
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

// MARK: - Navigate action (passed down the tree)

/// A lightweight typed-route navigator passed via Environment.
/// Screens call `navigate(.dashboard)` to push without coupling to `NavigationPath`.
public struct NavigateAction: Sendable {
    private let push: @MainActor @Sendable (Route) -> Void
    public init(_ push: @escaping @MainActor @Sendable (Route) -> Void) { self.push = push }
    @MainActor public func callAsFunction(_ route: Route) { push(route) }
}

private struct NavigateKey: EnvironmentKey {
    static let defaultValue = NavigateAction { _ in }
}

extension EnvironmentValues {
    public var navigate: NavigateAction {
        get { self[NavigateKey.self] }
        set { self[NavigateKey.self] = newValue }
    }
}
