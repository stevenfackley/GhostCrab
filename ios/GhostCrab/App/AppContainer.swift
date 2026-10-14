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
/// Android side. There is no test-only initialiser: ViewModel unit tests do
/// not build an `AppContainer`; they construct ViewModels directly with the
/// fakes in `GhostCrabTests/Support/Fakes.swift`.
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

    // MARK: - In-memory pairing hand-off

    /// One-shot token hand-off from QR scan / deep link to manual entry.
    /// In memory only; never persisted and never placed in `Route`.
    public let pairingHandoff: PairingHandoff

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

        // Pairing hand-off: shared by QrScan, the deep-link handler and ManualEntry.
        self.pairingHandoff = PairingHandoff()
    }

    // MARK: - ViewModel factories
    //
    // One per screen. ViewModels themselves are added by Wave 4 — these
    // factory methods will be filled in as their VMs land. Keeping the
    // skeleton here makes screen-side dependency wiring obvious: each
    // screen calls `container.makeXyzVM()`, no other DI plumbing required.

    /// Onboarding walkthrough VM. Reads/writes step state from `OnboardingRepository`.
    public func makeOnboardingVM() -> OnboardingViewModel {
        OnboardingViewModel(repository: self.onboarding)
    }

    /// Connection picker VM. Observes saved profiles + drives connect-to-profile.
    public func makeConnectionPickerVM() -> ConnectionPickerViewModel {
        ConnectionPickerViewModel(
            profileRepository: self.profiles,
            connectionManager: self.gateway
        )
    }

    /// Manual entry VM. The `prefillURL` flows through the screen so this factory
    /// stays parameter-free; the screen calls `vm.setPrefillURL(_:)` on appear,
    /// which also consumes any pairing token stashed in ``pairingHandoff``.
    public func makeManualEntryVM() -> ManualEntryViewModel {
        ManualEntryViewModel(
            connectionManager: self.gateway,
            profileRepository: self.profiles,
            onboardingRepository: self.onboarding,
            pairingHandoff: self.pairingHandoff
        )
    }

    /// LAN scan VM. Subscribes to `DiscoveryService.startDiscovery()` on appear.
    public func makeScanVM() -> ScanViewModel {
        ScanViewModel(discoveryService: self.discovery)
    }

    /// Builds a fresh `QrScanViewModel`. The screen wires `onNavigate` itself;
    /// pairing tokens go to ``pairingHandoff``, never into the route.
    public func makeQrScanVM() -> QrScanViewModel {
        QrScanViewModel(pairingHandoff: pairingHandoff)
    }

    /// Builds a fresh `AIRecommendationViewModel` wired to the gateway + config + models.
    public func makeAIRecommendationVM() -> AIRecommendationViewModel {
        AIRecommendationViewModel(
            ai: ai,
            gateway: gateway,
            config: config,
            models: models
        )
    }

    /// Builds a fresh `SettingsViewModel` wired to the settings + onboarding repos.
    public func makeSettingsVM() -> SettingsViewModel {
        SettingsViewModel(
            settings: settings,
            onboarding: onboarding
        )
    }

    /// Config Editor ViewModel — depends on the config repository and the
    /// gateway connection manager. Auto-loads when the gateway transitions to
    /// `.connected` and resets to ``ConfigEditorUiState/disconnected`` on drop.
    public func makeConfigEditorVM() -> ConfigEditorViewModel {
        ConfigEditorViewModel(config: config, gateway: gateway)
    }

    /// Installed Skills ViewModel — depends on the installed-skill repository.
    /// Subscribes to ``InstalledSkillRepository/observeInstalled()`` on
    /// `start()` for live list updates.
    public func makeInstalledSkillsVM() -> InstalledSkillsViewModel {
        InstalledSkillsViewModel(skills: skills)
    }

    /// Dashboard ViewModel — depends on the gateway connection manager (for
    /// live status), the model repository (for the active-model card), and
    /// the AI service (for the AI tile gate).
    public func makeDashboardVM() -> DashboardViewModel {
        DashboardViewModel(gateway: gateway, models: models, ai: ai)
    }

    /// Model Manager ViewModel — depends on the model repository.
    public func makeModelManagerVM() -> ModelManagerViewModel {
        ModelManagerViewModel(models: models)
    }
}

// MARK: - Environment injection

/// `nil` by default on purpose. A view that reads `\.appContainer` without the
/// scene root having injected one has a wiring bug. The old default
/// (`AppContainer()`) silently built a second, disconnected container with its
/// own `GatewayConnectionManagerImpl`, stores and `NWBrowserDiscoveryService`,
/// so such a view showed "disconnected" while the real session was live.
private struct AppContainerKey: EnvironmentKey {
    static let defaultValue: AppContainer? = nil
}

extension EnvironmentValues {
    /// Write side of ``appContainer``. Inject once in ``GhostCrabApp`` via
    /// `.environment(\.injectedAppContainer, container)`.
    ///
    /// Separate from the trapping read side because SwiftUI's
    /// `.environment(_:_:)` writes through a `WritableKeyPath`, and a key-path
    /// write to a computed property runs its getter first: a settable
    /// `appContainer` would trap on the parent environment, which by definition
    /// has no container yet.
    public var injectedAppContainer: AppContainer? {
        get { self[AppContainerKey.self] }
        set { self[AppContainerKey.self] = newValue }
    }

    /// Top-level dependency container. Read inside any view as
    /// `@Environment(\.appContainer) var container`.
    ///
    /// Traps (`preconditionFailure`) when read from a hierarchy that was never
    /// given a container. Previews of container-reading screens must inject one
    /// through ``injectedAppContainer``.
    public var appContainer: AppContainer {
        guard let container = injectedAppContainer else {
            preconditionFailure("AppContainer was not injected. Add .environment(\\.injectedAppContainer, container) at the scene root.")
        }
        return container
    }
}
