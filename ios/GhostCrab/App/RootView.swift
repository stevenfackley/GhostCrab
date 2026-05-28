import SwiftUI

/// Top-level navigation container. Picks the start destination based on
/// onboarding state, then dispatches every named ``Route`` to its
/// corresponding screen.
///
/// On iPad and Mac Catalyst the content is width-capped so long lines stay
/// readable in landscape — the Android single-pane layout maps to a
/// max-width-constrained iOS single stack rather than `NavigationSplitView`
/// in v1.
struct RootView: View {

    @Environment(\.appContainer) private var container
    @State private var path = NavigationPath()
    @State private var startRoute: Route?

    var body: some View {
        NavigationStack(path: $path) {
            startGate
                .navigationDestination(for: Route.self) { destination(for: $0) }
        }
        .task { await resolveStartRoute() }
        .frame(maxWidth: .infinity)
    }

    // MARK: - Start gate

    @ViewBuilder
    private var startGate: some View {
        if let startRoute {
            destination(for: startRoute)
                .environment(\.navigate, NavigateAction { route in path.append(route) })
        } else {
            // Pre-resolution splash — solid abyss + lockup. Brief; resolves on first frame.
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
        if !completed {
            startRoute = .onboarding
        } else {
            // Future: if a stored profile auto-connects on launch, jump straight to dashboard.
            // For now, always land on the connection picker so the user picks/resumes a profile.
            startRoute = .connectionPicker
        }
    }

    // MARK: - Route dispatch

    @ViewBuilder
    private func destination(for route: Route) -> some View {
        switch route {
        case .onboarding:
            OnboardingScreen(vm: container.makeOnboardingVM())
        case .connectionPicker:
            ConnectionPickerScreen(vm: container.makeConnectionPickerVM())
        case .manualEntry(let prefillURL):
            ManualEntryScreen(vm: container.makeManualEntryVM(), prefillURL: prefillURL)
        case .qrScan:
            QrScanScreen()
        case .scan:
            ScanScreen(vm: container.makeScanVM())
        case .dashboard:
            DashboardScreen(vm: container.makeDashboardVM())
        case .configEditor:
            ConfigEditorScreen(viewModel: container.makeConfigEditorVM())
        case .modelManager:
            ModelManagerScreen(vm: container.makeModelManagerVM())
        case .aiRecommendation:
            AIRecommendationScreen()
        case .installedSkills:
            InstalledSkillsScreen(viewModel: container.makeInstalledSkillsVM())
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

// MARK: - Wave-4 placeholder

/// Shown for any route whose real screen hasn't landed yet (i3–i7).
/// Wave-4 agents replace each `PendingScreen` call with the real screen view.
private struct PendingScreen: View {
    let title: String
    let route: Route
    var subtitle: String?

    var body: some View {
        ZStack {
            DesignTokens.Color.abyss.ignoresSafeArea()
            EmptyState(
                icon: "hammer",
                title: title,
                message: subtitle ?? "Screen scaffolded — implementation lands in wave 4."
            )
        }
        .navigationTitle(title)
        #if os(iOS)
        .toolbarBackground(DesignTokens.Color.abyssRaised, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
        #endif
    }
}
