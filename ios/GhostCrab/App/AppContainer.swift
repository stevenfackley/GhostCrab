import Foundation
import SwiftUI

/// Single composition root for the GhostCrab iOS app.
///
/// Built once at app launch in ``GhostCrabApp`` and injected into the SwiftUI
/// view hierarchy via Environment. Holds all long-lived repositories and
/// services as protocol-typed properties (so consumers depend on the
/// `Domain/Repository/` contracts, not concrete impls), with one factory
/// method per screen ViewModel to centralise wiring.
///
/// Equivalent to the Koin `AppModule + DataModule + UiModule` triple on the
/// Android side. Tests construct ``AppContainer`` with mock repositories by
/// using the test-only initialiser.
@MainActor
public final class AppContainer {

    // MARK: - Storage primitives (singletons)

    private let settingsStore: SettingsStore
    private let profileStore: ProfileStore

    // MARK: - Concrete connection manager (also exposed via the protocol below)
    //
    // Several gateway-backed impls need the *concrete* `GatewayConnectionManagerImpl`
    // because they reach into `requireClient()`, which isn't on the public
    // `GatewayConnectionManager` protocol. The protocol property below is the
    // same instance, just downcast for consumers that should depend on the
    // contract only.
    private let connectionManagerImpl: GatewayConnectionManagerImpl

    // MARK: - Public protocol-typed repositories

    public let settings: any SettingsRepository
    public let profiles: any ConnectionProfileRepository
    public let onboarding: any OnboardingRepository
    public let gateway: any GatewayConnectionManager
    public let config: any ConfigRepository
    public let models: any ModelRepository
    public let ai: any AIRecommendationService
    public let skills: any InstalledSkillRepository
    public let scopeProbe: any ScopeProbe
    public let discovery: any DiscoveryService

    // MARK: - Init

    /// Production wiring. Call once from `GhostCrabApp`.
    public init() {
        // Storage primitives
        let settingsStore = SettingsStore()
        let profileStore = ProfileStore()
        self.settingsStore = settingsStore
        self.profileStore = profileStore

        // Repositories backed by stores
        self.settings = SettingsRepositoryImpl(store: settingsStore)
        self.profiles = ConnectionProfileRepositoryImpl(store: profileStore)
        self.onboarding = OnboardingRepositoryImpl(store: settingsStore)

        // Gateway connection manager — concrete instance is shared with
        // repositories that need `requireClient()` access.
        let gatewayImpl = GatewayConnectionManagerImpl(
            settingsRepository: self.settings
        )
        self.connectionManagerImpl = gatewayImpl
        self.gateway = gatewayImpl

        // Repositories backed by the active gateway client
        self.config = ConfigRepositoryImpl(connectionManager: gatewayImpl)
        self.models = ModelRepositoryImpl(connectionManager: gatewayImpl)
        self.ai = AIRecommendationServiceImpl(connectionManager: gatewayImpl)
        self.skills = InstalledSkillRepositoryImpl(connectionManager: gatewayImpl)
        self.scopeProbe = ScopeProbeImpl(connectionManager: gatewayImpl)

        // Discovery is independent — wraps NWBrowser directly.
        self.discovery = NWBrowserDiscoveryService()
    }

    // MARK: - ViewModel factories
    //
    // One per screen. ViewModels themselves are added by Wave 4 — these
    // factory methods will be filled in as their VMs land. Keeping the
    // skeleton here makes screen-side dependency wiring obvious: each
    // screen calls `container.makeXyzVM()`, no other DI plumbing required.

    // TODO[wave-4]: ViewModel factories land here once VMs exist.
    // public func makeOnboardingVM() -> OnboardingViewModel { ... }
    // public func makeConnectionPickerVM() -> ConnectionPickerViewModel { ... }
    // public func makeManualEntryVM(prefillURL: URL? = nil) -> ManualEntryViewModel { ... }
    // public func makeScanVM() -> ScanViewModel { ... }
    // public func makeQrScanVM() -> QrScanViewModel { ... }
    // public func makeDashboardVM() -> DashboardViewModel { ... }
    // public func makeConfigEditorVM() -> ConfigEditorViewModel { ... }
    // public func makeModelManagerVM() -> ModelManagerViewModel { ... }
    // public func makeAIRecommendationVM() -> AIRecommendationViewModel { ... }
    // public func makeInstalledSkillsVM() -> InstalledSkillsViewModel { ... }
    // public func makeSettingsVM() -> SettingsViewModel { ... }
}

// MARK: - Environment injection

private struct AppContainerKey: EnvironmentKey {
    @MainActor static let defaultValue: AppContainer = AppContainer()
}

extension EnvironmentValues {
    /// Top-level dependency container. Set once in ``GhostCrabApp`` via
    /// `.environment(\.appContainer, ...)`; read inside any view as
    /// `@Environment(\.appContainer) var container`.
    public var appContainer: AppContainer {
        get { self[AppContainerKey.self] }
        set { self[AppContainerKey.self] = newValue }
    }
}
