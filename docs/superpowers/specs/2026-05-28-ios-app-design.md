# iOS App Design: GhostCrab for iOS — Full Feature Parity

**Date:** 2026-05-28
**Status:** Draft
**Scope:** New native iOS app reaching v1.0 with feature parity to the Android app shipped at v0.1.0. Lives in the same monorepo under `ios/`. Connects to the same OpenClaw Gateway, mirrors the same brand and behavior, ships via TestFlight.

---

## Problem

CLAUDE.md currently lists iOS under "Out of Scope (v1.0)." The Android app has shipped and is in use; an iOS counterpart with the same surface unblocks anyone preferring iPhone/iPad as their gateway client. The decision is no longer *whether* to ship iOS but *how* — what stack, what code reuse with the Kotlin codebase, what architecture, and how to phase the work without burning months before the first running build.

## Solution Overview

Build a **pure SwiftUI** app in a new `ios/` folder of the existing monorepo, mirroring the Android architecture **screen-for-screen and protocol-for-protocol**. No Kotlin Multiplatform, no Compose Multiplatform. Frozen Swift `protocol`s in `Domain/Repository/` map 1:1 to the frozen Kotlin `interface`s in `app/.../domain/repository/`. Each Android screen has a SwiftUI counterpart using the same name, the same state shape, and the same user-facing copy. Distribution via TestFlight from the same self-hosted macOS runner (`steve-mac-mini`) that already runs the Android CI.

The architecture choice prioritizes **mental coherence between platforms** over code reuse. Porting a feature becomes a translation exercise (Kotlin → Swift, same shape), not a redesign. KMP would have shared the data layer but doubled the build complexity and added a Kotlin/Native toolchain to iOS work. Compose Multiplatform would have shared more but is still maturing on iOS in 2026 and would compromise native feel. Pure SwiftUI is the lowest-risk path with the clearest mental model.

---

## Foundational decisions (locked 2026-05-28)

| Decision | Value | Why |
|---|---|---|
| Stack | Pure SwiftUI | Thin client + frozen contracts = SwiftUI fits without a sharing layer |
| Scope of v1 | Full parity with Android | User explicitly chose "full parity in one push" over MVP-first |
| Repo layout | Monorepo `ios/` folder | Single source of truth for shared docs/specs/ADRs |
| Minimum iOS | 18.0 | `@Observable` macro, Swift Testing, modern NWBrowser, NavigationStack with typed routes |
| Distribution | TestFlight | Personal + early-adopter audience; future App Store eligible without refactor |
| Architecture | MVVM with `@Observable` ViewModels + protocol-based services | Mirrors Koin-style injection on Android; minimal boilerplate; fully testable |
| DI | Hand-rolled `AppContainer` via SwiftUI Environment | No third-party DI library; single composition root |
| Bundle ID | `com.qavren.ghostcrab` | `com.openclaw.*` is taken globally on Apple's namespace |
| Apple Team ID | `QJW4S8BDFX` | Steve's individual developer account |
| Display name | `GhostCrab` | Same as Android |

---

## Project structure

```
ios/
├── GhostCrab.xcodeproj
├── GhostCrab/
│   ├── App/
│   │   ├── GhostCrabApp.swift           # @main, builds AppContainer
│   │   ├── AppContainer.swift           # DI composition root + ViewModel factories
│   │   └── RootView.swift               # NavigationStack + Route dispatch
│   ├── Domain/
│   │   ├── Model/                       # mirrors app/.../domain/model/
│   │   ├── Error/                       # GatewayError enum (mirrors GatewayException sealed hierarchy)
│   │   ├── Repository/                  # protocols only — FROZEN as v1.0 contracts
│   │   └── Util/                        # pure helpers (IP literal checks, etc.)
│   ├── Data/
│   │   ├── Api/
│   │   │   ├── OpenClawAPIClient.swift          # actor, URLSession + async/await
│   │   │   ├── DTO/                              # Codable structs
│   │   │   └── CleartextPublicIPGuard.swift     # URLProtocol guard (mirrors OkHttp interceptor)
│   │   ├── Discovery/
│   │   │   └── NWBrowserDiscoveryService.swift  # Bonjour for _openclaw-gw._tcp
│   │   ├── Storage/
│   │   │   ├── ProfileStore.swift               # UserDefaults (metadata) + Keychain (tokens)
│   │   │   └── SettingsStore.swift              # UserDefaults
│   │   └── Impl/                                # Repository implementations
│   ├── UI/
│   │   ├── Components/                          # GlassSurface, HttpSecurityBanner, CodeBlock, …
│   │   ├── Theme/
│   │   │   ├── DesignTokens.swift               # mirrors BrandTokens.kt
│   │   │   └── Fonts.swift                      # Inter, JetBrains Mono
│   │   ├── Navigation/
│   │   │   └── Route.swift                      # typed enum, Hashable
│   │   ├── Onboarding/
│   │   ├── Connection/
│   │   ├── Dashboard/
│   │   ├── Config/
│   │   ├── Models/
│   │   ├── AI/
│   │   └── Settings/
│   ├── Crash/
│   │   └── PrivacySafeLogger.swift              # scrubs Authorization, tokens, IPs from os_log
│   └── Resources/
│       ├── Assets.xcassets                       # icons from IconKitchen-Output/ios/
│       └── Fonts/                                # Inter + JetBrains Mono .ttfs
├── GhostCrabTests/                              # Swift Testing (@Test, iOS 18+)
└── GhostCrabUITests/                            # XCUITest for E2E flows
```

Mirrors `app/src/main/kotlin/com/openclaw/ghostcrab/` near 1:1. Renaming `domain/exception/` → `Domain/Error/` because Swift uses `Error`, not "exception."

---

## Domain layer

### Swift mirror of the Kotlin domain

| Kotlin (`domain/`) | Swift (`Domain/`) | Notes |
|---|---|---|
| `sealed interface GatewayException` | `enum GatewayError: Error` | Associated values: url, statusCode, underlying |
| `data class ConnectionProfile` | `struct ConnectionProfile: Codable, Identifiable, Sendable` | `UUID` for id |
| `sealed interface GatewayConnection` | `enum GatewayConnection: Sendable` | `.disconnected/.connecting(url)/.connected(...)/.error(url, error)` |
| `interface GatewayConnectionManager` | `protocol GatewayConnectionManager: Sendable` | All methods `async throws`; `AsyncStream<GatewayConnection>` replaces `StateFlow` |
| `interface ConnectionProfileRepository` | `protocol ConnectionProfileRepository: Sendable` | `AsyncSequence<[ConnectionProfile]>` for live updates |
| `interface ConfigRepository` | `protocol ConfigRepository: Sendable` | Same surface |
| `interface ModelRepository` | `protocol ModelRepository: Sendable` | Same surface |
| `interface SettingsRepository` | `protocol SettingsRepository: Sendable` | Same surface |
| `interface OnboardingRepository` | `protocol OnboardingRepository: Sendable` | Same surface |
| `interface InstalledSkillRepository` | `protocol InstalledSkillRepository: Sendable` | Same surface |
| `interface AIRecommendationService` | `protocol AIRecommendationService: Sendable` | Same surface |
| `domain/util/IpLiteralChecks.kt` | `Domain/Util/IPLiteral.swift` | Pure functions |

### Frozen contract discipline

The Swift protocols in `Domain/Repository/` are the v1.0 iOS contract surface. Same rule as Kotlin: implementations change, signatures don't. Any change requires an ADR in `docs/adr/`. The next available ADR number is `0005` and onward.

### Sendable everywhere

Every model, error, and protocol is `Sendable` so the codebase compiles cleanly under Swift 6 strict concurrency. Forces deliberate boundary-crossing rather than implicit shared state.

---

## Data layer

### `OpenClawAPIClient`

```swift
actor OpenClawAPIClient {
    private let baseURL: URL
    private let session: URLSession
    private let decoder: JSONDecoder

    init(baseURL: URL, token: String? = nil, allowCleartextPublicIPs: Bool = false) {
        // … config: ephemeral session, 15s connect, 30s request, register CleartextPublicIPGuard,
        //   set Authorization header from token, lenient decoder for upstream JSON quirks
    }

    func health() async throws -> HealthResponse
    func status() async throws -> StatusResponse
    func getConfig() async throws -> (sections: [String: AnyCodable], etag: String?)
    func updateConfig(section: String, value: AnyCodable, etag: String?) async throws
    func getModels() async throws -> [ModelDTO]
    func setActiveModel(_ id: String) async throws
    func getAIRecommendation(_ request: AIRecommendationRequestDTO) async throws -> AIRecommendationResponseDTO
    func close()
}
```

- `actor` for thread-safety (mirrors Kotlin `Mutex` on the connection manager).
- Lenient decoding: status/config/models all tolerate non-JSON responses by returning defaults — mirrors the existing tolerance in `OpenClawApiClient.kt` for the upstream `ghcr.io/openclaw/openclaw` quirk that serves HTML at `/status` and `/config`.
- Error mapping (direct port of `OpenClawApiClient.kt:308–340`):
  - `URLError.cannotConnectToHost` / `.notConnectedToInternet` → `GatewayError.unreachable`
  - `URLError.timedOut` → `GatewayError.timeout`
  - `URLError.serverCertificate*` → `GatewayError.tls`
  - HTTP 401/403 → `GatewayError.auth(statusCode:)`
  - `DecodingError` → `GatewayError.api(statusCode: 0, message: "deserialization failed: \(detail)")`
  - All other `URLError` → `GatewayError.unreachable`
- Authorization headers redacted from logs via `PrivacySafeLogger` (see Crash/Logging section).

### `CleartextPublicIPGuard`

`URLProtocol` subclass registered on the session config. Direct port of `CleartextPublicIpInterceptor.kt` — blocks HTTP requests to public IP literals unless the user has explicitly opted in via Settings. Loopback, RFC-1918 private ranges, and link-local pass through. Hostnames are never blocked (no double DNS resolution).

### `NWBrowserDiscoveryService`

```swift
final class NWBrowserDiscoveryService: DiscoveryService {
    func services() -> AsyncStream<[DiscoveredService]> { /* NWBrowser wrapping */ }
}
```

Uses `Network.framework`'s `NWBrowser` with `NWBrowser.Descriptor.bonjour(type: "_openclaw-gw._tcp", domain: nil)`. Service type matches the Kotlin `NsdDiscoveryServiceImpl` constant exactly.

**Critical Info.plist requirement:** `NSBonjourServices` array must contain `_openclaw-gw._tcp` or iOS 14+ silently blocks the browser. Easy foot-gun.

### Storage

- **ProfileStore** — `UserDefaults` for connection profile metadata (Codable round-trip). Tokens never touch UserDefaults.
- **Token storage** — `Keychain` via `SecItemAdd`/`SecItemCopyMatching` with attributes:
  - `kSecClass: kSecClassGenericPassword`
  - `kSecAttrAccessible: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` (no iCloud sync, equivalent privacy posture to Android's `EncryptedSharedPreferences` AES256_GCM)
  - `kSecAttrService: "com.qavren.ghostcrab.token"`
  - `kSecAttrAccount: <profile UUID>`
- **SettingsStore** — `UserDefaults` for booleans like `allowCleartextPublicIPs`.
- **No SwiftData.** Two stores (profiles + settings) doesn't justify the schema-migration machinery.

### Crash / logging

- `os_log` with subsystem `"com.qavren.ghostcrab"` and per-module categories.
- `PrivacySafeLogger` wraps `os_log`, regex-scrubs `Authorization: Bearer .*` and IP literals before formatting. Direct port of `PrivacySafeUncaughtExceptionHandler.kt`.
- Sensitive fields formatted with `%{private}@` (redacted in Console.app outside debug builds); safe fields use `%{public}@`.

---

## UI layer

### Navigation

Typed `Route` enum + `NavigationStack`:

```swift
enum Route: Hashable {
    case connectionPicker
    case manualEntry(prefillURL: URL?)
    case qrScan
    case scan
    case onboarding
    case dashboard
    case configEditor
    case modelManager
    case aiRecommendation
    case installedSkills
    case settings
}
```

Matches Compose Navigation's `Routes` sealed-class idiom. `NavigationPath` (in `RootView`) is mutated by VMs through a `NavigationCoordinator` actor — same indirection as Android's typed `NavGraph`.

### Screen parity map

11 screens, all required for v1 (matches `app/.../ui/`):

| Android (`ui/.../Screen.kt`) | iOS (`UI/.../Screen.swift`) | iOS-native replacements |
|---|---|---|
| `OnboardingScreen` (multi-step pager) | `OnboardingScreen` | `TabView(.page)` for step pager, `.task` for first-run setup |
| `ConnectionPickerScreen` | `ConnectionPickerScreen` | `List` with `.swipeActions` for delete |
| `ScanScreen` (mDNS pulse) | `ScanScreen` | `NWBrowser` AsyncStream → `@State [DiscoveredService]`; `.symbolEffect(.pulse)` |
| `QrScanScreen` (CameraX) | `QrScanScreen` | `DataScannerViewController` (iOS 16+) via `UIViewControllerRepresentable` |
| `ManualEntryScreen` | `ManualEntryScreen` | `Form`/`Section`; `TextField(.URL)`; "Use HTTPS" `Toggle` |
| `DashboardScreen` | `DashboardScreen` | `ScrollView` + cards; `Grid` for tile layout on iPad |
| `ConfigEditorScreen` (diff sheet, ETag, NoConfigApi state) | `ConfigEditorScreen` | `TextEditor` monospaced; `.sheet` for diff; same `NoConfigApi` empty state ported from 2026-05-28 Android fix |
| `ModelManagerScreen` | `ModelManagerScreen` | `List` with checkmark for active; `.refreshable` |
| `AIRecommendationScreen` | `AIRecommendationScreen` | `TextField(axis: .vertical)`; `.sheet` for proposed patch |
| `InstalledSkillsScreen` | `InstalledSkillsScreen` | `List` with row `Menu` for install/uninstall |
| `SettingsScreen` | `SettingsScreen` | `Form` with `Section`s — most idiomatic iOS settings shape |

### Theme — `DesignTokens.swift`

Mirrors `BrandTokens.kt` verbatim:

```swift
enum DesignTokens {
    enum Color {
        static let abyss        = SwiftUI.Color(hex: 0x0F1115)
        static let cyanPrimary  = SwiftUI.Color(hex: 0x5BE9FF)
        static let cyanPulse    = SwiftUI.Color(hex: 0x7BD8FF)
        static let amberWarn    = SwiftUI.Color(hex: 0xE0A458)
        static let crimsonError = SwiftUI.Color(hex: 0xFF4D6D)
        static let glass        = SwiftUI.Color.white.opacity(0.06)
    }
    enum Spacing { static let xs: CGFloat = 4; static let sm: CGFloat = 8; static let md: CGFloat = 16; static let lg: CGFloat = 24; static let xl: CGFloat = 32 }
    enum Font {
        static func body(_ size: CGFloat) -> SwiftUI.Font { .custom("Inter-Regular", size: size) }
        static func mono(_ size: CGFloat) -> SwiftUI.Font { .custom("JetBrainsMono-Regular", size: size) }
    }
}
```

Same usage rules from Android port verbatim:

- Surfaces use `GlassSurface` view (6% white over abyss). No solid gray cards.
- HTTP-mode: amber banner. HTTP + `auth: none`: amber background + crimson outline.
- Error messages explicit: exact URL, HTTP status code, exception class. No "Oops" copy.
- Animations only for state transitions and active-scan pulse. No decorative motion.
- IP/port/JSON/CLI output → JetBrains Mono. UI text → Inter.

### Shared components

`UI/Components/`: `GlassSurface`, `HttpSecurityBanner`, `ConnectionStatusBar`, `CodeBlock` (uses `UIPasteboard` instead of `LocalClipboardManager`), `EnumDropdownRow` (→ `Picker`), `ErrorRow`, `EmptyState` (factored out — used by both `NoConfigApi` and empty model list).

### iPad layout

Pure `NavigationStack` (no `NavigationSplitView` in v1 — matches Android single-pane). One concession: `.frame(maxWidth: 600)` on root content keeps lines readable in landscape iPad. No per-screen work.

---

## DI / app composition

```swift
@MainActor
final class AppContainer {
    let settings: SettingsRepository
    let profiles: ConnectionProfileRepository
    let gateway: GatewayConnectionManager
    let config: ConfigRepository
    let models: ModelRepository
    let onboarding: OnboardingRepository
    let skills: InstalledSkillRepository
    let ai: AIRecommendationService
    let discovery: DiscoveryService

    init() {
        self.settings = SettingsRepositoryImpl(store: SettingsStore())
        self.profiles = ConnectionProfileRepositoryImpl(store: ProfileStore())
        self.gateway  = GatewayConnectionManagerImpl(settings: settings)
        self.config   = ConfigRepositoryImpl(gateway: gateway)
        self.models   = ModelRepositoryImpl(gateway: gateway)
        self.onboarding = OnboardingRepositoryImpl(store: SettingsStore())
        self.skills   = InstalledSkillRepositoryImpl(gateway: gateway)
        self.ai       = AIRecommendationServiceImpl(gateway: gateway)
        self.discovery = NWBrowserDiscoveryService()
    }

    // One factory per screen — matches Koin's viewModel { … } block
    func makeManualEntryVM()     -> ManualEntryViewModel     { .init(gateway: gateway, profiles: profiles, onboarding: onboarding) }
    func makeDashboardVM()       -> DashboardViewModel       { .init(gateway: gateway, models: models) }
    func makeConfigEditorVM()    -> ConfigEditorViewModel    { .init(config: config) }
    func makeModelManagerVM()    -> ModelManagerViewModel    { .init(models: models) }
    func makeAIRecommendationVM() -> AIRecommendationViewModel { .init(ai: ai) }
    func makeOnboardingVM()      -> OnboardingViewModel      { .init(onboarding: onboarding) }
    func makeScanVM()            -> ScanViewModel            { .init(discovery: discovery) }
    func makeQrScanVM()          -> QrScanViewModel          { .init() }
    func makeConnectionPickerVM() -> ConnectionPickerViewModel { .init(profiles: profiles, gateway: gateway) }
    func makeInstalledSkillsVM() -> InstalledSkillsViewModel { .init(skills: skills) }
    func makeSettingsVM()        -> SettingsViewModel        { .init(settings: settings) }
}

private struct AppContainerKey: EnvironmentKey {
    @MainActor static let defaultValue = AppContainer()
}

extension EnvironmentValues {
    var appContainer: AppContainer {
        get { self[AppContainerKey.self] }
        set { self[AppContainerKey.self] = newValue }
    }
}

@main
struct GhostCrabApp: App {
    @State private var container = AppContainer()
    var body: some Scene {
        WindowGroup { RootView().environment(\.appContainer, container) }
    }
}
```

Each screen reads the container from environment, builds its VM, holds it as `@State`. Tests build a container with mock protocols.

---

## Testing strategy

### Targets

- **`GhostCrabTests`** — Swift Testing (`@Test`, iOS 18+).
  - Repository impls — instantiate with fake stores/clients, drive operations, assert state.
  - `OpenClawAPIClient` — `URLProtocol` subclass (`MockURLProtocol`) registered on session config returns canned responses. Direct analog to Ktor's `MockEngine` pattern in `OpenClawApiClientTest.kt`.
  - ViewModels — instantiate with mock protocols, drive actions async, assert state.
- **`GhostCrabUITests`** — XCUITest.
  - End-to-end onboarding → connected flow.
  - Connect → config empty-state explainer renders ("No editable configuration" — matches the 2026-05-28 Android `NoConfigApi` fix).
  - Scan list populates from a fake Bonjour responder (test-time NWListener publishing `_openclaw-gw._tcp`).

### Coverage parity

Match the Android `app/src/test/` unit test count (currently ~8 files: `OpenClawApiClientTest`, `GatewayConnectionManagerImplTest`, `ConfigRepositoryImplTest`, `ModelRepositoryImplTest`, etc.). Each gets a Swift counterpart with the same scenarios.

---

## Distribution & signing

### Provisioning

- **Apple Team:** `QJW4S8BDFX` (Steve, individual developer account).
- **Bundle ID:** `com.qavren.ghostcrab` (registered, explicit App ID).
- **Code signing:** Xcode-managed automatic signing on `steve-mac-mini`. Xcode creates and rotates dev + distribution certs and provisioning profiles. No manual `.mobileprovision` checked in.

### Required Info.plist keys

| Key | Value | Reason |
|---|---|---|
| `CFBundleIdentifier` | `com.qavren.ghostcrab` | Match registered App ID |
| `CFBundleShortVersionString` | mirror `versionName` from `app/build.gradle.kts` | Match Android version |
| `CFBundleVersion` | mirror `versionCode` from `app/build.gradle.kts` | Match Android build |
| `NSCameraUsageDescription` | "Scan QR codes to pair with an OpenClaw gateway." | QR scanner |
| `NSLocalNetworkUsageDescription` | "Discover OpenClaw gateways on your network." | LAN scan / NWBrowser |
| `NSBonjourServices` | `["_openclaw-gw._tcp"]` | Without this, iOS 14+ silently blocks the browser |
| `ITSAppUsesNonExemptEncryption` | `false` | Only uses OS-built-in TLS; qualifies for standard exemption |

### CI workflow

New file `.github/workflows/ios-release.yml`:

- **Triggers:** `workflow_dispatch` (manual) + tag `ios-v*` push (versioned releases). Never auto on main push.
- **Runs on:** `[self-hosted, macOS, ARM64]` (same runner as Android CI).
- **Steps:**
  1. `actions/checkout@v6`
  2. Write `$ASC_KEY_P8` to `~/.appstoreconnect/private_keys/AuthKey_$ASC_KEY_ID.p8` (Apple's expected layout).
  3. `xcodebuild -scheme GhostCrab -configuration Release -archivePath build/GhostCrab.xcarchive archive`
  4. `xcodebuild -exportArchive -archivePath build/GhostCrab.xcarchive -exportPath build/ -exportOptionsPlist ios/ExportOptions.plist`
  5. `xcrun altool --upload-app -f build/GhostCrab.ipa -t ios --apiKey "$ASC_KEY_ID" --apiIssuer "$ASC_ISSUER_ID"`
  6. `finally:` wipe the temp `.p8` file.
- **Secrets consumed:**
  - `ASC_KEY_ID`
  - `ASC_ISSUER_ID`
  - `ASC_KEY_P8`

---

## Phased implementation

Even with "full parity in one push" as the scope, the **implementation** decomposes into 8 shippable sub-phases. Each phase ends with a green CI build and a buildable archive.

| Phase | Scope | Est. |
|---|---|---|
| **i1** | Xcode project scaffold (`GhostCrab.xcodeproj`), folder structure, `GhostCrabApp` shell, `DesignTokens`, fonts, asset catalog from `IconKitchen-Output/ios/`, Info.plist privacy strings, `.github/workflows/ios-release.yml`, first green CI build | 1 day |
| **i2** | Domain + Data: all Swift models, `GatewayError` enum, all repository protocols, `OpenClawAPIClient` with all endpoints + lenient decoding, `CleartextPublicIPGuard`, `NWBrowserDiscoveryService`, `ProfileStore` + Keychain token storage, repository impls, unit tests for each | 3 days |
| **i3** | Connect flow: `OnboardingScreen`, `ConnectionPickerScreen`, `ManualEntryScreen`, `ScanScreen`. **First device install milestone** — cold start → enter URL → see Dashboard placeholder | 2 days |
| **i4** | `DashboardScreen`, `ModelManagerScreen` | 2 days |
| **i5** | `ConfigEditorScreen` (most complex: diff sheet, ETag conflict UX, `NoConfigApi` empty state) | 3 days |
| **i6** | `QrScanScreen` + Cloudflare pairing prefill into `ManualEntryScreen` | 2 days |
| **i7** | `AIRecommendationScreen`, `InstalledSkillsScreen`, `SettingsScreen` | 2 days |
| **i8** | Polish, App Store Connect agreements signed, first TestFlight upload | 2 days |
| **Total** | | **~17 working days** |

---

## Out of scope for v1

Same as Android v1.0:

- WebSocket streaming
- Offline config cache
- Agent runtime on-device
- Provider-API keys on-device
- Multi-active-connection
- In-app purchases / StoreKit

iPad-specific layouts (`NavigationSplitView`, multi-column) are also out of scope — single-pane on both iPhone and iPad.

---

## Open questions / risks

| Risk | Mitigation |
|---|---|
| Apple's App Store Connect "Latest Agreements" banner can block first upload | Surface check during i8 polish; flag if it bites |
| Xcode-managed signing occasionally fails CI with "no provisioning profile" if the cert was renewed mid-build | Manual cert rotation is the escape hatch; rare enough to not pre-build for |
| `NWBrowser` requires `NSBonjourServices` declaration — easy to forget, silent failure | Included in i1 Info.plist; UI tests in i3 will catch absence |
| Lenient JSON decoding of upstream `/health` `{"status": true}` — Swift's `JSONDecoder` is stricter than `kotlinx.serialization` in lenient mode | Use a custom decoding strategy for `HealthResponse` that accepts boolean OR string for the `status` field |
| `xcrun altool` is deprecated in favor of `xcrun notarytool` for some workflows | `altool --upload-app` is still supported for App Store / TestFlight uploads as of Xcode 26.x; swap if it gets removed |
| Drift between Android and iOS over time | Cross-platform-feature ADRs land in `docs/adr/`; both apps reference the same ADR before implementing |

---

## Related

- ADR-001 (`docs/adr/0001-kotlin-compose.md`) — Android stack choice; iOS choice is parallel reasoning for a different platform
- ADR-002 (`docs/adr/0002-auth-mode-probing.md`) — same auth probing algorithm ports to iOS verbatim
- Memory: `project_ios_app_identifiers.md`, `project_ios_app_design_decisions.md`
