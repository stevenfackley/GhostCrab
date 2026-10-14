# GhostCrab iOS Finish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the 2026-10-13 iOS audit's release blockers and P1/P2 findings: QR and deep-link pairing that keeps the token out of navigation state, a model-activation confirm, bundled fonts, a privacy manifest, an explicit ATS decision, VM lifecycle and in-flight guards, typed errors instead of `try?`, honest timeouts, a per-client cleartext guard, real tests, a PR CI lane and a clean TestFlight export.

**Architecture:** Pure SwiftUI with `@Observable @MainActor` ViewModels and hand-rolled DI in `AppContainer`. The frozen `Domain/Repository` protocols are not touched; every new type is additive. Secrets cross screens only through an in-memory one-shot `PairingHandoff` owned by `AppContainer`, never through `Route`. Networking stays in the `OpenClawAPIClient` actor, and per-request policy (timeouts, cleartext opt-in) moves from process-global state onto each `URLRequest`.

**Tech Stack:** Swift 6 (`SWIFT_STRICT_CONCURRENCY: complete`), SwiftUI, iOS 18 / Mac Catalyst 14, XcodeGen, Swift Testing (`import Testing`) for unit tests, XCTest/XCUITest for the launch smoke test, GitHub Actions on the self-hosted Mac Mini runner.

---

## Conventions (read once)

- This plan was written on Windows. Every build and test step is marked **⚠ must run on the Mac Mini (steve-mac-mini, Xcode)**. Steps that need a human (downloads, App Store Connect, device checks) are marked **⚠ manual**.
- Names come from `ios/project.yml`: project `GhostCrab.xcodeproj` (generated, untracked), scheme `GhostCrab`, targets `GhostCrab`, `GhostCrabTests`, `GhostCrabUITests`.
- Simulator: commands use `name=iPhone 16` (Xcode 16). On Xcode 26, substitute any name from `xcrun simctl list devices available | grep iPhone` (for example `iPhone 17`).
- Always run `xcodegen generate` before `xcodebuild`. New files only show up after regenerating.
- Every build rewrites `ios/GhostCrab/App/BuildInfo.swift` (git SHA injection by `ios/Scripts/inject-build-info.sh`). Never stage it. Each commit step starts with `git checkout -- ios/GhostCrab/App/BuildInfo.swift`.
- Unit tests use Swift Testing (`@Suite`, `@Test`, `#expect`, `#require`), like the existing `GhostCrabTests/*`. Suites run in parallel, so tests never share mutable global state (see `MockURLProtocol`, Task 15).
- Do not edit `ios/GhostCrab/Domain/Repository/*.swift`. Those signatures are frozen.
- Work on a feature branch (`git switch -c feat/ios-finish`), never on `main`. Open one PR at the end and squash-merge it. Commit messages follow Conventional Commits with a subject and a body only: no co-author trailer and no AI attribution of any kind.
- Baseline: Task 1 Step 5 also runs the existing unit suite. If it fails before you change anything, stop and report that first.

## Task dependency order

1. **Task 1 runs first.** Until `ios/GhostCrabUITests/` contains a tracked file, `xcodegen generate` fails on a clean checkout. That blocks every later verification step and both CI workflows.
2. Task 2 (test fakes) comes before Tasks 6, 7, 9 and 19–22.
3. Tasks 3, 4 and 8 run in that order (all touch `RootView.swift` or `AppContainer.swift`).
4. Tasks 5, 6, 7 and 8 run in that order (pairing: decoder, then hand-off, then QR, then deep link).
5. Tasks 6, 12 and 19 run in that order (all edit `ManualEntryViewModel.swift` and `ManualEntryViewModelTests.swift`).
6. Task 13 comes before Task 14 (storage).
7. Task 15 comes before Tasks 16, 17 and 18, and Task 17 comes before Task 18 (both edit the client's `init` and `send`).
8. Tasks 9, 10, 11, 20, 21, 22, 23 and 25 are independent once Tasks 1 and 2 are done.
9. Task 24 (PR CI lane) needs Task 1. Merge it last so its first run exercises the whole suite.

## File structure

| File | Status | Responsibility |
|---|---|---|
| `ios/GhostCrabUITests/LaunchSmokeTests.swift` | create | XCUITest: app boots, onboarding shows, Skip reaches the gateway picker |
| `ios/GhostCrabTests/Support/Fakes.swift` | create | Lock-protected fakes for every Domain repository protocol |
| `ios/GhostCrabTests/Support/PairingLinkFixture.swift` | create | Builds `ghostcrab://pair?p=…` links for tests |
| `ios/GhostCrabTests/Support/InMemoryTokenStore.swift` | create | `TokenStoring` fake with switchable read/delete failures |
| `ios/GhostCrabTests/Support/MockURLProtocol.swift` | rewrite | Per-host stub registry, safe under parallel Swift Testing |
| `ios/GhostCrab/UI/Navigation/LazyViewModelHost.swift` | create | Builds a screen's VM once per destination (`@State` + `.onAppear`) |
| `ios/GhostCrab/App/RootView.swift` | modify | Uses `LazyViewModelHost`; drops dead `PendingScreen`; handles `onOpenURL` |
| `ios/GhostCrab/App/AppContainer.swift` | modify | Optional env key with precondition; owns `PairingHandoff`; wires VMs |
| `ios/GhostCrab/Domain/Util/PairingLink.swift` | create | Pure decoder for `ghostcrab://pair?p=<base64url JSON>` |
| `ios/GhostCrab/UI/Navigation/PairingHandoff.swift` | create | One-shot in-memory token hand-off (QR / deep link → manual entry) |
| `ios/GhostCrab/UI/Connection/QrScanViewModel.swift` | rewrite | Uses `PairingLink`; stashes the token; navigates with the URL only |
| `ios/GhostCrab/UI/Connection/QrScanScreen.swift` | modify | Navigation callback takes a `URL` |
| `ios/GhostCrab/UI/Connection/ManualEntryViewModel.swift` | modify | Takes the paired token; ATS hint; tracked connect task |
| `ios/GhostCrab/UI/Connection/ManualEntryScreen.swift` | modify | ATS warning under the host field |
| `ios/GhostCrab/UI/Models/ModelManagerViewModel.swift` | rewrite | Two-step activation (request, confirm); one call per confirm; error survives the reload |
| `ios/GhostCrab/UI/Models/ModelManagerScreen.swift` | modify | `confirmationDialog` |
| `ios/GhostCrab/UI/Theme/Fonts.swift` | modify | `AppFont.PostScriptName` constants |
| `ios/GhostCrab/Resources/Fonts/*.ttf`, `LICENSE-*.txt` | add | Inter (4 weights), JetBrains Mono (2 weights), OFL licenses |
| `ios/GhostCrab/Resources/PrivacyInfo.xcprivacy` | create | Privacy manifest (UserDefaults CA92.1 only) |
| `ios/GhostCrab/Domain/Util/ATSCleartextPolicy.swift` | create | Predicts whether ATS blocks plain HTTP to a host |
| `docs/adr/0006-ios-ats-local-networking.md` | create | ATS decision (0005 is reserved) |
| `docs/IMPLEMENTATION_PLAN.md` | modify | Lists ADR-006 |
| `ios/GhostCrab/UI/Settings/SettingsScreen.swift` | modify | Footer copy explains what ATS does |
| `ios/GhostCrab/Data/Storage/Keychain.swift` | modify | Adds the `TokenStoring` protocol; `Keychain` conforms |
| `ios/GhostCrab/Data/Storage/ProfileStore.swift` | modify | Quarantines a corrupt blob and throws `ProfileStoreError` on write |
| `ios/GhostCrab/Data/Impl/ConnectionProfileRepositoryImpl.swift` | modify | Logs a token-cleanup failure instead of `try?` |
| `ios/GhostCrab/Data/Api/OpenClawAPIClient.swift` | modify | Test seam; strict JSON decode; `Timeouts`; per-client cleartext flag |
| `ios/GhostCrab/Data/Api/CleartextPublicIPGuard.swift` | modify | Reads the opt-in from a `URLProtocol` request property, not a global |
| `ios/GhostCrab/UI/Connection/ConnectionPickerViewModel.swift` | modify | Tracked connect and delete tasks with guards |
| `ios/GhostCrab/UI/Onboarding/OnboardingViewModel.swift` | rewrite | One tracked transition task; hydration never overrides a tap |
| `ios/GhostCrab/UI/Dashboard/DashboardViewModel.swift`, `DashboardScreen.swift` | modify | Tracked loads; `onDisappear`; guarded disconnect |
| `ios/GhostCrab/UI/Config/ConfigEditorViewModel.swift` | modify | Tracked load and save tasks with guards |
| `ios/GhostCrab/UI/AI/AIRecommendationViewModel.swift`, `AIRecommendationScreen.swift` | modify | Tracked submit and apply tasks; cancel on disappear |
| `ios/GhostCrab/UI/Settings/SettingsViewModel.swift` | modify | Serialised toggle writes; guarded reset |
| `ios/GhostCrabTests/**/*Tests.swift` | create | One suite per unit under test (named in each task) |
| `ios/project.yml` | modify | Test-target plist settings, URL scheme, fonts, privacy resource, ATS, version |
| `ios/ExportOptions.plist` | modify | `destination=export`; drops `compileBitcode` |
| `.github/workflows/ios-release.yml` | modify | Roster runner label; key wipe that works on macOS |
| `.github/workflows/ios-pr.yml` | create | PR lane: xcodegen + `xcodebuild test` on `mac-mini-ios` |

---

### Task 1: Make `xcodegen generate` work on a clean checkout with an XCUITest launch smoke test

**Files:**
- Create: `ios/GhostCrabUITests/LaunchSmokeTests.swift`
- Modify: `ios/project.yml:75-94` (settings for the two test targets)

Decision: keep the `GhostCrabUITests` target, because the spec (§Testing) wants XCUITest coverage, and give it one launch smoke test. The directory is empty and untracked (git does not track empty directories), so on a fresh clone, CI included, `xcodegen generate` stops with a spec-validation error for the missing source path. `ios-release.yml` fails the same way.

- [ ] **Step 1: Reproduce on a clean clone** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

```bash
rm -rf /tmp/gc-clean && git clone --depth 1 "$(git remote get-url origin)" /tmp/gc-clean
cd /tmp/gc-clean/ios && xcodegen generate; echo "exit=$?"
```
Expected: XcodeGen prints a spec validation error naming the missing `GhostCrabUITests` source directory, followed by `exit=1`.

- [ ] **Step 2: Write the smoke test**

Create `ios/GhostCrabUITests/LaunchSmokeTests.swift`:

```swift
import XCTest

/// Launch smoke test: the app boots with its production `AppContainer`, shows
/// onboarding on a first run, and Skip reaches the gateway picker.
///
/// Onboarding state is forced through `NSArgumentDomain`: `-key value` launch
/// arguments override `UserDefaults.standard` for this launch only, so the
/// test is deterministic on a reused simulator.
final class LaunchSmokeTests: XCTestCase {

    @MainActor
    func testFirstLaunchShowsOnboardingAndSkipReachesGatewayPicker() throws {
        continueAfterFailure = false

        let app = XCUIApplication()
        app.launchArguments += [
            "-com.qavren.ghostcrab.onboarding.completed", "NO",
            "-com.qavren.ghostcrab.onboarding.current_step", "welcome",
        ]
        app.launch()

        XCTAssertTrue(
            app.buttons["Get Started"].waitForExistence(timeout: 15),
            "Onboarding welcome step did not appear"
        )

        app.buttons["Skip"].tap()

        XCTAssertTrue(
            app.navigationBars["Gateways"].waitForExistence(timeout: 10),
            "Skip did not reach the gateway picker"
        )
    }
}
```

- [ ] **Step 3: Add plist generation to both test targets**

In `ios/project.yml`, replace the two test-target `settings` blocks:

```yaml
  GhostCrabTests:
    type: bundle.unit-test
    platform: iOS
    sources: [GhostCrabTests]
    dependencies:
      - target: GhostCrab
    settings:
      base:
        GENERATE_INFOPLIST_FILE: YES
        BUNDLE_LOADER: "$(TEST_HOST)"
        TEST_HOST: "$(BUILT_PRODUCTS_DIR)/GhostCrab.app/$(BUNDLE_EXECUTABLE_FOLDER_PATH)/GhostCrab"

  GhostCrabUITests:
    type: bundle.ui-testing
    platform: iOS
    sources: [GhostCrabUITests]
    dependencies:
      - target: GhostCrab
    settings:
      base:
        GENERATE_INFOPLIST_FILE: YES
        TEST_TARGET_NAME: GhostCrab
```

- [ ] **Step 4: Run the smoke test** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

```bash
cd ios && xcodegen generate && xcodebuild test -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:GhostCrabUITests/LaunchSmokeTests -quiet
```
Expected: `** TEST SUCCEEDED **`.

- [ ] **Step 5: Run the existing unit suite as a baseline** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

```bash
cd ios && xcodebuild test -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:GhostCrabTests -quiet
```
Expected: `** TEST SUCCEEDED **` (`HealthResponseDecodingTests`, `PrivacySafeLoggerTests`, `CleartextPublicIPLiteralTests`).

- [ ] **Step 6: Commit**

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add ios/GhostCrabUITests/LaunchSmokeTests.swift ios/project.yml
git commit -m "test(ios): add XCUITest launch smoke test so xcodegen works on clean checkouts" -m "GhostCrabUITests/ was an empty, untracked directory, so a fresh clone (CI included) failed xcodegen spec validation. One smoke test keeps the target and pins boot + onboarding skip. Test targets now generate their Info.plist."
```

---

### Task 2: Hand-written fakes for the Domain protocols

**Files:**
- Create: `ios/GhostCrabTests/Support/Fakes.swift`

- [ ] **Step 1: Write the fakes**

Create `ios/GhostCrabTests/Support/Fakes.swift`:

```swift
import Foundation
@testable import GhostCrab

// Hand-written fakes for the frozen `Domain/Repository` protocols.
//
// Each fake is a `final class` guarded by an `NSLock` and marked
// `@unchecked Sendable`. The protocols are `Sendable` and some requirements
// are synchronous (`GatewayConnectionManager.currentState`), which rules out
// actors. Behaviour is fixed at init and every call is recorded for
// assertions. Streams replay one snapshot and then finish, so `for await`
// loops in ViewModels end instead of leaking.

// MARK: - GatewayConnectionManager

final class FakeGatewayConnectionManager: GatewayConnectionManager, @unchecked Sendable {
    private let lock = NSLock()
    private let connectError: GatewayError?
    private var state: GatewayConnection = .disconnected
    private var connectLog: [(url: String, token: String?)] = []
    private var disconnects = 0

    init(connectError: GatewayError? = nil) {
        self.connectError = connectError
    }

    var connectURLs: [String] { lock.withLock { connectLog.map { $0.url } } }
    var connectTokens: [String?] { lock.withLock { connectLog.map { $0.token } } }
    var disconnectCount: Int { lock.withLock { disconnects } }

    var currentState: GatewayConnection { lock.withLock { state } }

    func connectionStates() -> AsyncStream<GatewayConnection> {
        let snapshot = currentState
        return AsyncStream { continuation in
            continuation.yield(snapshot)
            continuation.finish()
        }
    }

    func probeAuth(url: String) async throws -> AuthRequirement {
        AuthRequirement.none
    }

    func connect(url: String, token: String?) async throws {
        lock.withLock { connectLog.append((url: url, token: token)) }
        if let connectError { throw connectError }
        lock.withLock {
            state = .connected(
                url: url,
                displayName: "Fake Gateway",
                version: "test",
                authRequirement: token == nil ? AuthRequirement.none : AuthRequirement.token,
                isHttps: url.lowercased().hasPrefix("https://"),
                capabilities: []
            )
        }
    }

    func disconnect() async {
        lock.withLock {
            disconnects += 1
            state = .disconnected
        }
    }
}

// MARK: - ConnectionProfileRepository

final class FakeConnectionProfileRepository: ConnectionProfileRepository, @unchecked Sendable {
    private let lock = NSLock()
    private let deleteError: GatewayError?
    private var profiles: [ConnectionProfile]
    private var tokens: [String: String]
    private var saveLog: [(profile: ConnectionProfile, token: String?)] = []
    private var deleteLog: [String] = []

    init(
        profiles: [ConnectionProfile] = [],
        tokens: [String: String] = [:],
        deleteError: GatewayError? = nil
    ) {
        self.profiles = profiles
        self.tokens = tokens
        self.deleteError = deleteError
    }

    static func profile(url: String = "http://192.168.1.20:18789", hasToken: Bool = false) -> ConnectionProfile {
        ConnectionProfile(id: UUID(), displayName: "Basement", url: url, lastConnectedAt: nil, hasToken: hasToken)
    }

    var savedProfiles: [ConnectionProfile] { lock.withLock { saveLog.map { $0.profile } } }
    var savedTokens: [String?] { lock.withLock { saveLog.map { $0.token } } }
    var deleteAttempts: [String] { lock.withLock { deleteLog } }

    func observeProfiles() -> AsyncStream<[ConnectionProfile]> {
        let snapshot = lock.withLock { profiles }
        return AsyncStream { continuation in
            continuation.yield(snapshot)
            continuation.finish()
        }
    }

    func saveProfile(_ profile: ConnectionProfile, token: String?) async throws {
        lock.withLock {
            saveLog.append((profile: profile, token: token))
            profiles.removeAll { $0.id == profile.id }
            profiles.append(profile)
            tokens[profile.id.uuidString] = token
        }
    }

    func getToken(profileId: String) async throws -> String? {
        lock.withLock { tokens[profileId] }
    }

    func deleteProfile(profileId: String) async throws {
        lock.withLock { deleteLog.append(profileId) }
        if let deleteError { throw deleteError }
        lock.withLock {
            profiles.removeAll { $0.id.uuidString == profileId }
            tokens[profileId] = nil
        }
    }
}

// MARK: - OnboardingRepository

final class FakeOnboardingRepository: OnboardingRepository, @unchecked Sendable {
    private let lock = NSLock()
    private var completed: Bool
    private var step: OnboardingStep
    private var stepLog: [OnboardingStep] = []
    private var markCompletedCalls = 0
    private var resetCalls = 0

    init(completed: Bool = false, savedStep: OnboardingStep = .welcome) {
        self.completed = completed
        self.step = savedStep
    }

    var savedSteps: [OnboardingStep] { lock.withLock { stepLog } }
    var markCompletedCount: Int { lock.withLock { markCompletedCalls } }
    var resetCount: Int { lock.withLock { resetCalls } }

    func isCompleted() async -> Bool { lock.withLock { completed } }

    func saveStep(_ step: OnboardingStep) async {
        lock.withLock {
            self.step = step
            stepLog.append(step)
        }
    }

    func getSavedStep() async -> OnboardingStep { lock.withLock { step } }

    func markCompleted() async {
        lock.withLock {
            completed = true
            markCompletedCalls += 1
        }
    }

    func reset() async {
        lock.withLock {
            completed = false
            step = .welcome
            resetCalls += 1
        }
    }
}

// MARK: - ModelRepository

final class FakeModelRepository: ModelRepository, @unchecked Sendable {
    private let lock = NSLock()
    private let setActiveError: GatewayError?
    private var models: [ModelInfo]
    private var setActiveLog: [String] = []
    private var getModelsCalls = 0

    init(models: [ModelInfo], setActiveError: GatewayError? = nil) {
        self.models = models
        self.setActiveError = setActiveError
    }

    static func model(_ id: String, active: Bool = false) -> ModelInfo {
        ModelInfo(id: id, provider: "openai", displayName: id.uppercased(), isActive: active, status: "ready", capabilities: [])
    }

    var setActiveCalls: [String] { lock.withLock { setActiveLog } }
    var getModelsCount: Int { lock.withLock { getModelsCalls } }

    func getModels() async throws -> [ModelInfo] {
        lock.withLock {
            getModelsCalls += 1
            return models
        }
    }

    func setActiveModel(modelId: String) async throws {
        lock.withLock { setActiveLog.append(modelId) }
        if let setActiveError { throw setActiveError }
        lock.withLock {
            models = models.map {
                ModelInfo(
                    id: $0.id,
                    provider: $0.provider,
                    displayName: $0.displayName,
                    isActive: $0.id == modelId,
                    status: $0.status,
                    capabilities: $0.capabilities
                )
            }
        }
    }
}

// MARK: - SettingsRepository

final class FakeSettingsRepository: SettingsRepository, @unchecked Sendable {
    private let lock = NSLock()
    private let writeDelays: [Duration]
    private var value: Bool
    private var writes: [Bool] = []
    private var writeCalls = 0

    /// - Parameter writeDelays: Delay before the nth write lands (call 0 uses
    ///   `writeDelays[0]`, and so on; missing entries mean no delay). Lets a
    ///   test make an earlier write finish after a later one.
    init(allowCleartext: Bool = false, writeDelays: [Duration] = []) {
        self.value = allowCleartext
        self.writeDelays = writeDelays
    }

    var writeLog: [Bool] { lock.withLock { writes } }

    func allowCleartextPublicIPs() -> AsyncStream<Bool> {
        let snapshot = lock.withLock { value }
        return AsyncStream { continuation in
            continuation.yield(snapshot)
            continuation.finish()
        }
    }

    func setAllowCleartextPublicIPs(_ enabled: Bool) async {
        let delay: Duration = lock.withLock {
            defer { writeCalls += 1 }
            return writeCalls < writeDelays.count ? writeDelays[writeCalls] : .zero
        }
        if delay > .zero {
            do {
                try await Task.sleep(for: delay)
            } catch {
                return  // cancelled: drop the write, like a cancelled real write
            }
        }
        lock.withLock {
            value = enabled
            writes.append(enabled)
        }
    }
}

// MARK: - ConfigRepository

final class FakeConfigRepository: ConfigRepository, @unchecked Sendable {
    private let lock = NSLock()
    private var config: OpenClawConfig
    private var updates: [String] = []

    init(config: OpenClawConfig) {
        self.config = config
    }

    var updatedSections: [String] { lock.withLock { updates } }

    func getConfig() async throws -> OpenClawConfig { lock.withLock { config } }

    func updateConfig(section: String, value: AnyCodable) async throws {
        lock.withLock {
            updates.append(section)
            var sections = config.sections
            sections[section] = value
            config = OpenClawConfig(sections: sections, etag: config.etag)
        }
    }
}

// MARK: - AIRecommendationService

final class FakeAIRecommendationService: AIRecommendationService, Sendable {
    private let available: Bool
    private let suggestedChanges: [SuggestedChange]

    init(available: Bool = false, suggestedChanges: [SuggestedChange] = []) {
        self.available = available
        self.suggestedChanges = suggestedChanges
    }

    func isAvailable() async -> Bool { available }

    func getRecommendation(query: String, context: RecommendationContext) async throws -> AIRecommendation {
        AIRecommendation(query: query, recommendation: "Use the fake model.", suggestedChanges: suggestedChanges)
    }
}
```

- [ ] **Step 2: Compile and run the unit suite** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

```bash
cd ios && xcodegen generate && xcodebuild test -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:GhostCrabTests -quiet
```
Expected: `** TEST SUCCEEDED **` (the fakes compile; the existing suites still pass).

- [ ] **Step 3: Commit**

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add ios/GhostCrabTests/Support/Fakes.swift
git commit -m "test(ios): add hand-written fakes for the Domain repository protocols" -m "Lock-protected, call-recording fakes for GatewayConnectionManager, ConnectionProfileRepository, OnboardingRepository, ModelRepository, SettingsRepository, ConfigRepository and AIRecommendationService. Used by the ViewModel suites."
```

---

### Task 3: Build each screen's ViewModel once with `LazyViewModelHost`, and delete `PendingScreen`

**Files:**
- Create: `ios/GhostCrab/UI/Navigation/LazyViewModelHost.swift`
- Modify: `ios/GhostCrab/App/RootView.swift` (whole file)

Audit correction: the audit says screens taking `vm:` "lose state". They don't: every one stores it with `@State(initialValue:)`, which keeps the first instance. The real defect is that `destination(for:)` calls `container.makeXVM()` on every body evaluation, so every re-render builds a throwaway VM and runs its init side effects (`ConnectionPickerViewModel.init` starts two stream subscriptions; `OnboardingViewModel.init` spawns a hydration `Task`). The fix is the lazy pattern `QrScanScreen`, `AIRecommendationScreen` and `SettingsScreen` already use inline (`@State private var vm: VM?`, created once in `.onAppear`). It is factored into one generic host instead of being pasted into 8 screens, so the screens keep their `init(vm:)` and stay previewable.

- [ ] **Step 1: Create the host**

Create `ios/GhostCrab/UI/Navigation/LazyViewModelHost.swift`:

```swift
import SwiftUI

/// Builds a screen's ViewModel exactly once per destination identity.
///
/// `navigationDestination(for:)` re-runs its closure on every parent body
/// pass. Calling `container.makeXVM()` inline therefore built a throwaway VM
/// on every re-render and ran its init side effects (stream subscriptions,
/// hydration tasks), even though the screen's `@State(initialValue:)` kept
/// only the first instance. This host keeps the VM in `@State` and creates it
/// lazily in `.onAppear`, the same pattern `QrScanScreen`,
/// `AIRecommendationScreen` and `SettingsScreen` use inline.
struct LazyViewModelHost<ViewModel: AnyObject, Content: View>: View {

    private let make: @MainActor () -> ViewModel
    private let content: (ViewModel) -> Content

    @State private var vm: ViewModel?

    /// - Parameters:
    ///   - make: Factory called once, on first appearance. Usually a `container.makeXVM()` call.
    ///   - content: Builds the screen from the VM.
    init(
        make: @escaping @MainActor () -> ViewModel,
        @ViewBuilder content: @escaping (ViewModel) -> Content
    ) {
        self.make = make
        self.content = content
    }

    var body: some View {
        ZStack {
            if let vm {
                content(vm)
            } else {
                DesignTokens.Color.abyss.ignoresSafeArea()
            }
        }
        .onAppear {
            if vm == nil { vm = make() }
        }
    }
}
```

- [ ] **Step 2: Rewrite `RootView.swift`**

Replace the whole content of `ios/GhostCrab/App/RootView.swift` (this drops the dead `PendingScreen`, old lines 107-132):

```swift
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
```

The audit's "auto-resume last profile" stub comment (old line 49) is gone along with the `if/else`. Auto-resume is out of scope for this plan (see Coverage map).

- [ ] **Step 3: Build, then run the UI smoke test and the unit suite** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

```bash
cd ios && xcodegen generate && xcodebuild test -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=iOS Simulator,name=iPhone 16' -quiet
```
Expected: `** TEST SUCCEEDED **`.

- [ ] **Step 4: Manual navigation pass** ⚠ manual (simulator)

Run the app. Go to Picker, then Manual Entry, back, Scan, back, then Dashboard (via a mock gateway, `scripts/mock-gateway/` on port 18790), Models, back, Config, back. Expected: each screen loads once, and going back keeps the previous screen's state (scroll position, entered text).

- [ ] **Step 5: Commit**

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add ios/GhostCrab/UI/Navigation/LazyViewModelHost.swift ios/GhostCrab/App/RootView.swift
git commit -m "fix(ios): build each screen's ViewModel once per destination" -m "RootView.destination(for:) called container.makeXVM() on every body pass, creating throwaway VMs and re-running their init side effects. LazyViewModelHost holds the VM in @State and creates it once in onAppear. Removes the dead Wave-4 PendingScreen."
```

---

### Task 4: Stop `AppContainerKey` from silently building a second container

**Files:**
- Modify: `ios/GhostCrab/App/AppContainer.swift:12-14` (doc), `:164-178` (env key)

Decision: make the environment value optional and fail with a precondition. A cheap "unconfigured" container would need stub implementations of all 10 repositories and would still let a mis-wired view show a fake "disconnected" state. A precondition makes the wiring bug loud. Call sites are unchanged: `@Environment(\.appContainer)` still yields a non-optional `AppContainer`.

- [ ] **Step 1: Confirm no preview reads the container**

```bash
grep -rn "appContainer" ios/GhostCrab/UI/Components
```
Expected: no output. The only `#Preview` blocks are in `UI/Components` (`EmptyState`, `GlassSurface`, `HttpSecurityBanner`), and none of them read the container.

- [ ] **Step 2: Replace the environment key**

In `ios/GhostCrab/App/AppContainer.swift`, replace lines 164-178 (from `// MARK: - Environment injection` to the end of the file) with:

```swift
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
    /// Top-level dependency container. Set once in ``GhostCrabApp`` via
    /// `.environment(\.appContainer, ...)`; read inside any view as
    /// `@Environment(\.appContainer) var container`.
    ///
    /// Traps (`preconditionFailure`) when read from a hierarchy that was never
    /// given a container. Previews of container-reading screens must inject one.
    public var appContainer: AppContainer {
        get {
            guard let container = self[AppContainerKey.self] else {
                preconditionFailure("AppContainer was not injected. Add .environment(\\.appContainer, container) at the scene root.")
            }
            return container
        }
        set { self[AppContainerKey.self] = newValue }
    }
}
```

Also fix the stale class doc at lines 12-14. Replace:

```swift
/// Equivalent to the Koin `AppModule + DataModule + UiModule` triple on the
/// Android side. Tests construct ``AppContainer`` with mock repositories by
/// using the test-only initialiser.
```
with:
```swift
/// Equivalent to the Koin `AppModule + DataModule + UiModule` triple on the
/// Android side. Unit tests do not build an `AppContainer`; they construct
/// ViewModels directly with the fakes in `GhostCrabTests/Support/Fakes.swift`.
```

- [ ] **Step 3: Build and run the UI smoke test** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

```bash
cd ios && xcodegen generate && xcodebuild test -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:GhostCrabUITests/LaunchSmokeTests -quiet
```
Expected: `** TEST SUCCEEDED **`. The app boots, which proves `GhostCrabApp` injects the container before any read.

- [ ] **Step 4: Commit**

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add ios/GhostCrab/App/AppContainer.swift
git commit -m "fix(ios): make a missing AppContainer injection fail loudly" -m "AppContainerKey.defaultValue built a full second container (stores, Keychain, NWBrowser) whenever a view read the key without injection. The key is now optional and the accessor preconditions, so wiring bugs trap instead of showing split-brain state."
```

---

### Task 5: Pure `ghostcrab://pair` decoder

**Files:**
- Create: `ios/GhostCrab/Domain/Util/PairingLink.swift`
- Create: `ios/GhostCrabTests/Support/PairingLinkFixture.swift`
- Test: `ios/GhostCrabTests/Domain/PairingLinkTests.swift`

The payload is `ghostcrab://pair?p=<base64url JSON {"url": "...", "token": "...", "displayName": "..."}>`, the same shape as the Android plan's. Only `url` is required.

- [ ] **Step 1: Write the fixture and the failing tests**

Create `ios/GhostCrabTests/Support/PairingLinkFixture.swift`:

```swift
import Foundation

/// Builds `ghostcrab://pair?p=<base64url>` links the way the gateway pairing page does.
enum PairingLinkFixture {
    static func link(json: String) -> String {
        let base64url = Data(json.utf8).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        return "ghostcrab://pair?p=\(base64url)"
    }
}
```

Create `ios/GhostCrabTests/Domain/PairingLinkTests.swift`:

```swift
import Testing
import Foundation
@testable import GhostCrab

@Suite("PairingLink decoding")
struct PairingLinkTests {

    @Test func decodesURLTokenAndDisplayName() throws {
        let request = try PairingLink.decode(PairingLinkFixture.link(
            json: #"{"url":"http://192.168.1.20:18789","token":"tok_123","displayName":"Basement"}"#
        ))
        #expect(request.gatewayURL.absoluteString == "http://192.168.1.20:18789")
        #expect(request.token == "tok_123")
        #expect(request.displayName == "Basement")
    }

    @Test func missingTokenDecodesWithNilToken() throws {
        let request = try PairingLink.decode(PairingLinkFixture.link(json: #"{"url":"https://gw.example.com"}"#))
        #expect(request.gatewayURL.absoluteString == "https://gw.example.com")
        #expect(request.token == nil)
    }

    @Test func blankTokenIsTreatedAsMissing() throws {
        let request = try PairingLink.decode(PairingLinkFixture.link(json: #"{"url":"http://10.0.0.2:18789","token":"   "}"#))
        #expect(request.token == nil)
    }

    @Test func badBase64IsRejected() {
        #expect(throws: PairingLinkError.invalidBase64) {
            try PairingLink.decode("ghostcrab://pair?p=!!!!")
        }
    }

    @Test func base64ThatIsNotJSONIsRejected() {
        // "aGVsbG8" is base64url for "hello".
        #expect(throws: PairingLinkError.invalidJSON) {
            try PairingLink.decode("ghostcrab://pair?p=aGVsbG8")
        }
    }

    @Test(arguments: ["ftp://192.168.1.20", "javascript:alert(1)", "file:///etc/passwd", "http://"])
    func nonHTTPGatewayURLIsRejected(url: String) {
        #expect(throws: PairingLinkError.invalidGatewayURL) {
            try PairingLink.decode(PairingLinkFixture.link(json: "{\"url\":\"\(url)\"}"))
        }
    }

    @Test func missingPayloadIsRejected() {
        #expect(throws: PairingLinkError.missingPayload) {
            try PairingLink.decode("ghostcrab://pair")
        }
    }

    @Test func otherSchemesAreNotPairingLinks() {
        #expect(!PairingLink.isPairingLink("https://example.com/pair?p=abc"))
        #expect(throws: PairingLinkError.notAPairingLink) {
            try PairingLink.decode("https://example.com/pair?p=abc")
        }
    }

    @Test func schemeAndHostAreCaseInsensitive() throws {
        let link = PairingLinkFixture.link(json: #"{"url":"http://192.168.1.20:18789"}"#)
            .replacingOccurrences(of: "ghostcrab://pair", with: "GhostCrab://PAIR")
        #expect(try PairingLink.decode(link).gatewayURL.host == "192.168.1.20")
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

```bash
cd ios && xcodegen generate && xcodebuild test -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:GhostCrabTests/PairingLinkTests -quiet
```
Expected: the build fails with `cannot find 'PairingLink' in scope`, then `** TEST FAILED **`.

- [ ] **Step 3: Implement the decoder**

Create `ios/GhostCrab/Domain/Util/PairingLink.swift`:

```swift
import Foundation

/// A decoded `ghostcrab://pair` request.
public struct PairingRequest: Sendable, Equatable {
    /// Gateway base URL. Always `http` or `https` with a non-empty host.
    public let gatewayURL: URL
    /// Bearer token to pre-fill, or `nil` when the link carries none (or a blank one).
    public let token: String?
    /// Optional human label suggested by the gateway's pairing page.
    public let displayName: String?

    public init(gatewayURL: URL, token: String?, displayName: String?) {
        self.gatewayURL = gatewayURL
        self.token = token
        self.displayName = displayName
    }
}

/// Why a pairing link was rejected.
public enum PairingLinkError: Error, Equatable, Sendable {
    /// Scheme is not `ghostcrab` or host is not `pair`.
    case notAPairingLink
    /// No non-empty `p` query parameter.
    case missingPayload
    /// `p` is not valid base64url.
    case invalidBase64
    /// `p` decodes, but not to a JSON object with a string `url`.
    case invalidJSON
    /// `url` is not an `http`/`https` URL with a host.
    case invalidGatewayURL
}

extension PairingLinkError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .notAPairingLink:   return "not a ghostcrab://pair link"
        case .missingPayload:    return "missing `p` parameter"
        case .invalidBase64:     return "base64 decode failed"
        case .invalidJSON:       return "payload is not the expected JSON object"
        case .invalidGatewayURL: return "URL field is not an http(s) URL with a host"
        }
    }
}

/// Pure decoder for `ghostcrab://pair?p=<base64url JSON>` links.
///
/// Payload JSON: `{"url": "<http(s) gateway URL>", "token": "<optional bearer>",
/// "displayName": "<optional>"}`, the shape the gateway pairing page and the
/// Android client use. Only `url` is required. Used by the QR scanner and by
/// the app's `onOpenURL` handler.
public enum PairingLink {

    /// URL scheme registered in `project.yml` (`CFBundleURLTypes`).
    public static let scheme = "ghostcrab"

    /// Host that marks a pairing request.
    public static let host = "pair"

    /// Whether `raw` uses the `ghostcrab://pair` scheme and host, ignoring case.
    /// The payload is not validated.
    ///
    /// - Parameter raw: Candidate link string.
    /// - Returns: `true` for `ghostcrab://pair…` links.
    public static func isPairingLink(_ raw: String) -> Bool {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let comps = URLComponents(string: trimmed) else { return false }
        return comps.scheme?.lowercased() == scheme && comps.host?.lowercased() == host
    }

    /// Decodes and validates a pairing link.
    ///
    /// - Parameter raw: The full link, for example a QR payload or an `onOpenURL` URL's `absoluteString`.
    /// - Returns: The validated ``PairingRequest``. A blank token is normalised to `nil`.
    /// - Throws: ``PairingLinkError`` naming the first check that failed.
    public static func decode(_ raw: String) throws -> PairingRequest {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard isPairingLink(trimmed), let comps = URLComponents(string: trimmed) else {
            throw PairingLinkError.notAPairingLink
        }
        guard let payload = comps.queryItems?.first(where: { $0.name == "p" })?.value,
              !payload.isEmpty
        else {
            throw PairingLinkError.missingPayload
        }
        guard let json = base64URLDecode(payload) else {
            throw PairingLinkError.invalidBase64
        }
        let wire: Wire
        do {
            wire = try JSONDecoder().decode(Wire.self, from: json)
        } catch {
            throw PairingLinkError.invalidJSON
        }
        guard let url = URL(string: wire.url),
              let urlScheme = url.scheme?.lowercased(),
              urlScheme == "http" || urlScheme == "https",
              let urlHost = url.host, !urlHost.isEmpty
        else {
            throw PairingLinkError.invalidGatewayURL
        }
        let token = wire.token?.trimmingCharacters(in: .whitespacesAndNewlines)
        return PairingRequest(
            gatewayURL: url,
            token: (token?.isEmpty ?? true) ? nil : token,
            displayName: wire.displayName
        )
    }

    // MARK: - Internals

    private struct Wire: Decodable {
        let url: String
        let token: String?
        let displayName: String?
    }

    /// RFC 4648 §5 base64url (with `-`/`_`, optional padding) to raw bytes.
    private static func base64URLDecode(_ value: String) -> Data? {
        var base64 = value
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let remainder = base64.count % 4
        if remainder > 0 {
            base64.append(String(repeating: "=", count: 4 - remainder))
        }
        return Data(base64Encoded: base64)
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

```bash
cd ios && xcodegen generate && xcodebuild test -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:GhostCrabTests/PairingLinkTests -quiet
```
Expected: `** TEST SUCCEEDED **`.

- [ ] **Step 5: Commit**

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add ios/GhostCrab/Domain/Util/PairingLink.swift ios/GhostCrabTests/Support/PairingLinkFixture.swift ios/GhostCrabTests/Domain/PairingLinkTests.swift
git commit -m "feat(ios): add pure ghostcrab://pair link decoder" -m "PairingLink.decode validates scheme/host, the base64url p parameter, the JSON shape and an http(s) gateway URL with a host; a blank token becomes nil. Shared by the QR scanner and the upcoming onOpenURL handler."
```

---

### Task 6: `PairingHandoff`, so manual entry picks up the paired token

**Files:**
- Create: `ios/GhostCrab/UI/Navigation/PairingHandoff.swift`
- Modify: `ios/GhostCrab/UI/Connection/ManualEntryViewModel.swift:18-51` (deps + init), `:79-89` (`setPrefillURL`)
- Modify: `ios/GhostCrab/App/AppContainer.swift` (property + init + `makeManualEntryVM`)
- Test: `ios/GhostCrabTests/UI/PairingHandoffTests.swift`, `ios/GhostCrabTests/UI/ManualEntryViewModelTests.swift`

Why not `Route.manualEntry(prefillURL:token:)`: `Route` is `Hashable`, it lives in `NavigationPath`, and SwiftUI may encode it for state restoration, which could write the token to disk. The hand-off holds the token in memory until the next `take(for:)`. It reaches disk only through the normal path: connect, then `saveProfile`, then Keychain.

- [ ] **Step 1: Write the failing tests**

Create `ios/GhostCrabTests/UI/PairingHandoffTests.swift`:

```swift
import Testing
import Foundation
@testable import GhostCrab

@MainActor
@Suite("PairingHandoff")
struct PairingHandoffTests {

    @Test func takeReturnsTokenOnceForSameOrigin() throws {
        let handoff = PairingHandoff()
        let url = try #require(URL(string: "http://192.168.1.20:18789"))
        handoff.stash(token: "tok_123", for: url)
        #expect(handoff.take(for: url) == "tok_123")
        #expect(handoff.take(for: url) == nil)
    }

    @Test func hostComparisonIgnoresCase() throws {
        let handoff = PairingHandoff()
        handoff.stash(token: "tok_123", for: try #require(URL(string: "http://Gateway.LOCAL:18789")))
        #expect(handoff.take(for: try #require(URL(string: "http://gateway.local:18789"))) == "tok_123")
    }

    @Test func mismatchedOriginGetsNothingAndBurnsTheStash() throws {
        let handoff = PairingHandoff()
        let paired = try #require(URL(string: "http://192.168.1.20:18789"))
        handoff.stash(token: "tok_123", for: paired)
        #expect(handoff.take(for: try #require(URL(string: "http://192.168.1.99:18789"))) == nil)
        #expect(handoff.take(for: paired) == nil)
    }
}
```

Create `ios/GhostCrabTests/UI/ManualEntryViewModelTests.swift`:

```swift
import Testing
import Foundation
@testable import GhostCrab

@MainActor
@Suite("ManualEntryViewModel")
struct ManualEntryViewModelTests {

    private func makeVM(
        handoff: PairingHandoff,
        gateway: FakeGatewayConnectionManager = FakeGatewayConnectionManager(),
        profiles: FakeConnectionProfileRepository = FakeConnectionProfileRepository(),
        onboarding: FakeOnboardingRepository = FakeOnboardingRepository()
    ) -> ManualEntryViewModel {
        ManualEntryViewModel(
            connectionManager: gateway,
            profileRepository: profiles,
            onboardingRepository: onboarding,
            pairingHandoff: handoff
        )
    }

    @Test func prefillPicksUpStashedPairingTokenOnce() throws {
        let handoff = PairingHandoff()
        let url = try #require(URL(string: "http://192.168.1.20:18789"))
        handoff.stash(token: "tok_123", for: url)
        let vm = makeVM(handoff: handoff)

        vm.setPrefillURL(url)
        #expect(vm.host == "192.168.1.20")
        #expect(vm.port == "18789")
        #expect(vm.token == "tok_123")

        // The screen calls setPrefillURL on every appear; nothing is left to take.
        vm.token = ""
        vm.setPrefillURL(url)
        #expect(vm.token == "")
    }

    @Test func prefillWithoutStashLeavesTokenEmpty() throws {
        let vm = makeVM(handoff: PairingHandoff())
        vm.setPrefillURL(try #require(URL(string: "https://gw.example.com:8443")))
        #expect(vm.useHttps)
        #expect(vm.port == "8443")
        #expect(vm.token == "")
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

```bash
cd ios && xcodegen generate && xcodebuild test -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:GhostCrabTests/PairingHandoffTests -only-testing:GhostCrabTests/ManualEntryViewModelTests -quiet
```
Expected: the build fails with `cannot find 'PairingHandoff' in scope`, then `** TEST FAILED **`.

- [ ] **Step 3: Create `PairingHandoff`**

Create `ios/GhostCrab/UI/Navigation/PairingHandoff.swift`:

```swift
import Foundation

/// One-shot, in-memory hand-off of a pairing bearer token from the QR scanner
/// or a `ghostcrab://pair` deep link to the manual-entry form.
///
/// Why not `Route.manualEntry(prefillURL:token:)`: `Route` is `Hashable` and
/// lives in `NavigationPath`, which SwiftUI may encode for state restoration,
/// so a token there could be written to disk. Here it lives only in memory,
/// at most until the next ``take(for:)``, and reaches persistent storage only
/// through the normal connect, `ConnectionProfileRepository.saveProfile`,
/// Keychain path.
///
/// Owned by ``AppContainer`` (one per app process).
@MainActor
public final class PairingHandoff {

    private var pendingURL: URL?
    private var pendingToken: String?

    public init() {}

    /// Stores `token` for the gateway at `url`, replacing any earlier stash.
    ///
    /// - Parameters:
    ///   - token: Bearer token decoded from the pairing payload.
    ///   - url: Gateway base URL the token belongs to.
    public func stash(token: String, for url: URL) {
        pendingURL = url
        pendingToken = token
    }

    /// Returns the stashed token if `url` has the same scheme, host (case-insensitive)
    /// and port as the stashed URL.
    ///
    /// Always clears the stash, matching or not, so a token is read at most once
    /// and never reaches a different gateway.
    ///
    /// - Parameter url: The URL the manual-entry form was pre-filled with.
    /// - Returns: The token, or `nil` if nothing was stashed for that origin.
    public func take(for url: URL) -> String? {
        defer {
            pendingURL = nil
            pendingToken = nil
        }
        guard let pendingURL, let pendingToken, Self.sameOrigin(pendingURL, url) else {
            return nil
        }
        return pendingToken
    }

    static func sameOrigin(_ a: URL, _ b: URL) -> Bool {
        a.scheme?.lowercased() == b.scheme?.lowercased()
            && a.host?.lowercased() == b.host?.lowercased()
            && a.port == b.port
    }
}
```

- [ ] **Step 4: Wire `ManualEntryViewModel`**

In `ios/GhostCrab/UI/Connection/ManualEntryViewModel.swift`, replace the dependencies block and the init (lines 18-51):

```swift
    // MARK: - Dependencies

    private let connectionManager: any GatewayConnectionManager
    private let profileRepository: any ConnectionProfileRepository
    private let onboardingRepository: any OnboardingRepository
    private let pairingHandoff: PairingHandoff
```
(keep the `// MARK: - Form state` block unchanged), and:
```swift
    // MARK: - Init

    /// - Parameters:
    ///   - connectionManager: Drives the connect flow.
    ///   - profileRepository: Persists the profile + token after a successful connect.
    ///   - onboardingRepository: Marked completed after a successful connect.
    ///   - pairingHandoff: One-shot source of a token from QR / deep-link pairing.
    public init(
        connectionManager: any GatewayConnectionManager,
        profileRepository: any ConnectionProfileRepository,
        onboardingRepository: any OnboardingRepository,
        pairingHandoff: PairingHandoff
    ) {
        self.connectionManager = connectionManager
        self.profileRepository = profileRepository
        self.onboardingRepository = onboardingRepository
        self.pairingHandoff = pairingHandoff
    }
```

Replace `setPrefillURL(_:)` (lines 79-89):

```swift
    /// Parses a URL and populates scheme/host/port (e.g. from a QR scan or LAN result).
    ///
    /// If a pairing token was stashed for this URL's origin (QR or deep link),
    /// it is moved into ``token``. The hand-off is one-shot, so later calls
    /// (the screen calls this on every appear) leave ``token`` alone.
    ///
    /// - Parameter url: Gateway base URL.
    public func setPrefillURL(_ url: URL) {
        let scheme = url.scheme?.lowercased()
        let host = url.host ?? ""
        let parsedPort = url.port.flatMap { $0 > 0 ? String($0) : nil } ?? Self.defaultPort
        self.useHttps = (scheme == "https")
        self.host = host
        self.port = parsedPort
        self.hostError = nil
        self.portError = nil
        if let pairedToken = pairingHandoff.take(for: url) {
            self.token = pairedToken
        }
    }
```

- [ ] **Step 5: Wire `AppContainer`**

In `ios/GhostCrab/App/AppContainer.swift`, add after `public let discovery: any DiscoveryService`:

```swift

    // MARK: - In-memory pairing hand-off

    /// One-shot token hand-off from QR scan / deep link to manual entry.
    /// In memory only; never persisted and never placed in `Route`.
    public let pairingHandoff: PairingHandoff
```

In `init()`, add after `self.discovery = NWBrowserDiscoveryService()`:

```swift

        // Pairing hand-off: shared by QrScan, the deep-link handler and ManualEntry.
        self.pairingHandoff = PairingHandoff()
```

Replace `makeManualEntryVM()`:

```swift
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
```

- [ ] **Step 6: Run the tests to verify they pass** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

```bash
cd ios && xcodegen generate && xcodebuild test -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:GhostCrabTests/PairingHandoffTests -only-testing:GhostCrabTests/ManualEntryViewModelTests -quiet
```
Expected: `** TEST SUCCEEDED **`.

- [ ] **Step 7: Commit**

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add ios/GhostCrab/UI/Navigation/PairingHandoff.swift ios/GhostCrab/UI/Connection/ManualEntryViewModel.swift ios/GhostCrab/App/AppContainer.swift ios/GhostCrabTests/UI/PairingHandoffTests.swift ios/GhostCrabTests/UI/ManualEntryViewModelTests.swift
git commit -m "feat(ios): hand pairing tokens to manual entry without touching Route" -m "PairingHandoff is a one-shot in-memory stash owned by AppContainer. ManualEntryViewModel.setPrefillURL consumes a token stashed for the same origin. The token never enters the Hashable Route / NavigationPath."
```

---

### Task 7: The QR scanner keeps the pairing token

**Files:**
- Modify (rewrite): `ios/GhostCrab/UI/Connection/QrScanViewModel.swift`
- Modify: `ios/GhostCrab/UI/Connection/QrScanScreen.swift:43-51`
- Modify: `ios/GhostCrab/App/AppContainer.swift` (`makeQrScanVM`)
- Test: `ios/GhostCrabTests/UI/QrScanViewModelTests.swift`

Today `QrScanScreen.swift:46` drops the token (`new.onNavigate = { url, _ in`). After this task the callback has no slot for a token at all.

- [ ] **Step 1: Write the failing tests**

Create `ios/GhostCrabTests/UI/QrScanViewModelTests.swift`:

```swift
import Testing
import Foundation
@testable import GhostCrab

@MainActor
final class NavigationRecorder {
    var urls: [URL] = []
}

@MainActor
@Suite("QrScanViewModel")
struct QrScanViewModelTests {

    @Test func pairingLinkStashesTokenAndNavigatesWithURLOnly() throws {
        let handoff = PairingHandoff()
        let vm = QrScanViewModel(pairingHandoff: handoff)
        let recorder = NavigationRecorder()
        vm.onNavigate = { recorder.urls.append($0) }

        vm.onCodeScanned(PairingLinkFixture.link(json: #"{"url":"http://192.168.1.20:18789","token":"tok_123"}"#))

        let url = try #require(recorder.urls.first)
        #expect(url.absoluteString == "http://192.168.1.20:18789")
        #expect(vm.error == nil)
        #expect(handoff.take(for: url) == "tok_123")
    }

    @Test func pairingLinkWithoutTokenStashesNothing() throws {
        let handoff = PairingHandoff()
        let vm = QrScanViewModel(pairingHandoff: handoff)
        let recorder = NavigationRecorder()
        vm.onNavigate = { recorder.urls.append($0) }

        vm.onCodeScanned(PairingLinkFixture.link(json: #"{"url":"http://192.168.1.20:18789"}"#))

        let url = try #require(recorder.urls.first)
        #expect(handoff.take(for: url) == nil)
    }

    @Test func rawGatewayURLNavigates() {
        let vm = QrScanViewModel(pairingHandoff: PairingHandoff())
        let recorder = NavigationRecorder()
        vm.onNavigate = { recorder.urls.append($0) }

        vm.onCodeScanned("  https://gw.example.com:18789  ")

        #expect(recorder.urls.map(\.absoluteString) == ["https://gw.example.com:18789"])
    }

    @Test func malformedPairingLinkSurfacesErrorAndDoesNotNavigate() {
        let vm = QrScanViewModel(pairingHandoff: PairingHandoff())
        let recorder = NavigationRecorder()
        vm.onNavigate = { recorder.urls.append($0) }

        vm.onCodeScanned("ghostcrab://pair?p=!!!!")

        #expect(recorder.urls.isEmpty)
        #expect(vm.error == "Pairing QR code is malformed: base64 decode failed")
    }

    @Test func arbitraryTextIsRejected() {
        let vm = QrScanViewModel(pairingHandoff: PairingHandoff())
        vm.onCodeScanned("WIFI:S:home;T:WPA;P:hunter2;;")
        #expect(vm.error == "QR code doesn't contain a valid gateway URL")
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

```bash
cd ios && xcodegen generate && xcodebuild test -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:GhostCrabTests/QrScanViewModelTests -quiet
```
Expected: the build fails with `argument passed to call that takes no arguments` (`QrScanViewModel(pairingHandoff:)`), then `** TEST FAILED **`.

- [ ] **Step 3: Rewrite the ViewModel**

Replace the whole content of `ios/GhostCrab/UI/Connection/QrScanViewModel.swift` (the old private decoder, `PairingPayload` and `PairingDecodeError` are removed; `PairingLink` replaces them):

```swift
import Foundation
import Observation

/// ViewModel for `QrScanScreen`.
///
/// Accepts two payload shapes:
///
/// 1. **Raw URL**: `http://…` / `https://…`, used directly as the manual-entry prefill.
/// 2. **Pairing link**: `ghostcrab://pair?p=<base64url JSON>`, decoded by ``PairingLink``.
///    The gateway URL becomes the prefill. The bearer token (if any) is stashed
///    in ``PairingHandoff`` and is never passed to navigation, because `Route`
///    is `Hashable` and lives in `NavigationPath` / state restoration.
@MainActor
@Observable
public final class QrScanViewModel {

    // MARK: - State

    /// The last accepted gateway URL. Set right before ``onNavigate`` fires.
    public private(set) var lastDecoded: URL?

    /// Human-readable validation error for the last rejected payload, or `nil`.
    public private(set) var error: String?

    // MARK: - Side-effect callback

    /// Invoked with the gateway URL when a scan is accepted. Carries no secrets.
    /// The screen wires it to `navigate(.manualEntry(prefillURL:))`.
    public var onNavigate: (@MainActor (URL) -> Void)?

    // MARK: - Dependencies

    private let pairingHandoff: PairingHandoff

    // MARK: - Init

    /// - Parameter pairingHandoff: One-shot token hand-off shared with ``ManualEntryViewModel``.
    public init(pairingHandoff: PairingHandoff) {
        self.pairingHandoff = pairingHandoff
    }

    // MARK: - Public API

    /// Validates a payload decoded by the scanner.
    ///
    /// On success: stashes any pairing token, sets ``lastDecoded`` and invokes ``onNavigate``.
    /// On failure: sets ``error`` so the screen can surface it.
    ///
    /// - Parameter payload: Raw QR-decoded string.
    public func onCodeScanned(_ payload: String) {
        let trimmed = payload.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            error = "QR code is empty"
            return
        }

        if PairingLink.isPairingLink(trimmed) {
            do {
                let request = try PairingLink.decode(trimmed)
                if let token = request.token {
                    pairingHandoff.stash(token: token, for: request.gatewayURL)
                }
                accept(request.gatewayURL)
            } catch {
                self.error = "Pairing QR code is malformed: \(error.localizedDescription)"
            }
            return
        }

        let lower = trimmed.lowercased()
        guard lower.hasPrefix("http://") || lower.hasPrefix("https://"),
              let url = URL(string: trimmed),
              url.host != nil
        else {
            error = "QR code doesn't contain a valid gateway URL"
            return
        }
        accept(url)
    }

    /// Re-enables the scanner after an invalid scan was surfaced.
    public func clearError() {
        error = nil
    }

    // MARK: - Internals

    private func accept(_ url: URL) {
        error = nil
        lastDecoded = url
        onNavigate?(url)
    }
}
```

- [ ] **Step 4: Update the screen and the factory**

In `ios/GhostCrab/UI/Connection/QrScanScreen.swift`, replace lines 43-51:

```swift
        .onAppear {
            guard vm == nil else { return }
            let new = container.makeQrScanVM()
            new.onNavigate = { url in
                navigate(.manualEntry(prefillURL: url))
            }
            vm = new
        }
```

In `ios/GhostCrab/App/AppContainer.swift`, replace `makeQrScanVM()`:

```swift
    /// Builds a fresh `QrScanViewModel`. The screen wires `onNavigate` itself;
    /// pairing tokens go to ``pairingHandoff``, never into the route.
    public func makeQrScanVM() -> QrScanViewModel {
        QrScanViewModel(pairingHandoff: pairingHandoff)
    }
```

- [ ] **Step 5: Run the tests to verify they pass** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

```bash
cd ios && xcodegen generate && xcodebuild test -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:GhostCrabTests/QrScanViewModelTests -only-testing:GhostCrabTests/PairingLinkTests -quiet
```
Expected: `** TEST SUCCEEDED **`.

- [ ] **Step 6: Device check** ⚠ manual (physical iPhone; the simulator has no camera)

Open the gateway's pairing page, which shows a QR code for `ghostcrab://pair?p=…`, and scan it in-app. Expected: Manual Entry opens with host and port filled and the token field filled (masked). Tap Connect, and the profile saves with `hasToken`.

- [ ] **Step 7: Commit**

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add ios/GhostCrab/UI/Connection/QrScanViewModel.swift ios/GhostCrab/UI/Connection/QrScanScreen.swift ios/GhostCrab/App/AppContainer.swift ios/GhostCrabTests/UI/QrScanViewModelTests.swift
git commit -m "fix(ios): keep the pairing token from scanned ghostcrab://pair codes" -m "QrScanScreen dropped the token ({ url, _ in). QrScanViewModel now decodes via PairingLink, stashes the token in PairingHandoff and navigates with the URL only, so the token never enters Route."
```

---

### Task 8: Register the `ghostcrab://` scheme and handle `onOpenURL`

**Files:**
- Modify: `ios/project.yml` (target `info.properties`)
- Modify: `ios/GhostCrab/App/RootView.swift` (state, `onOpenURL`, alert, handler)

Security note: any app or web page can open `ghostcrab://pair`. The handler only pre-fills Manual Entry. Nothing connects until the user reads the host and taps Connect, so a hostile link cannot connect to a gateway or exfiltrate a token by itself.

- [ ] **Step 1: Register the URL scheme**

In `ios/project.yml`, under `targets.GhostCrab.info.properties`, insert after the `NSBonjourServices` list:

```yaml
        NSBonjourServices:
          - "_openclaw-gw._tcp"
        CFBundleURLTypes:
          - CFBundleURLName: com.qavren.ghostcrab.pairing
            CFBundleTypeRole: Viewer
            CFBundleURLSchemes:
              - ghostcrab
```

- [ ] **Step 2: Handle the URL in `RootView`**

In `ios/GhostCrab/App/RootView.swift`, add a state property under `@State private var startRoute: Route?`:

```swift
    @State private var deepLinkError: String?
```

Replace `body`:

```swift
    var body: some View {
        NavigationStack(path: $path) {
            startGate
                .navigationDestination(for: Route.self) { destination(for: $0) }
        }
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
```

Add the handler after `resolveStartRoute()`:

```swift
    // MARK: - Deep links

    /// Handles `ghostcrab://pair?p=…` from the OS camera, Safari or another app.
    ///
    /// The token goes to ``PairingHandoff``; only the gateway URL enters the
    /// navigation path. Nothing connects until the user taps Connect, so a
    /// hostile link can at most pre-fill a form the user can read and reject.
    /// The alert never echoes the payload (it contains the token).
    private func handleOpenURL(_ url: URL) {
        do {
            let request = try PairingLink.decode(url.absoluteString)
            if let token = request.token {
                container.pairingHandoff.stash(token: token, for: request.gatewayURL)
            }
            path.append(Route.manualEntry(prefillURL: request.gatewayURL))
        } catch {
            deepLinkError = "\(url.scheme ?? "?")://\(url.host ?? "?") \u{2014} \(error.localizedDescription)"
        }
    }
```

- [ ] **Step 3: Build and check the generated plist** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

```bash
cd ios && xcodegen generate && plutil -extract CFBundleURLTypes.0.CFBundleURLSchemes.0 raw GhostCrab/Resources/Info.plist && xcodebuild build -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=iOS Simulator,name=iPhone 16' -quiet
```
Expected: `ghostcrab`, then `** BUILD SUCCEEDED **`.

- [ ] **Step 4: Open a link in the simulator** ⚠ manual (simulator with the app installed)

```bash
P=$(printf '%s' '{"url":"http://192.168.1.20:18789","token":"tok_demo"}' | base64 | tr '+/' '-_' | tr -d '=\n')
xcrun simctl openurl booted "ghostcrab://pair?p=$P"
xcrun simctl openurl booted "ghostcrab://pair?p=!!!!"
```
Expected: the first link opens Manual Entry with host `192.168.1.20`, port `18789` and the token filled (masked). The second shows the alert "Pairing link rejected" with `ghostcrab://pair — base64 decode failed` and no token text.

- [ ] **Step 5: Commit**

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add ios/project.yml ios/GhostCrab/App/RootView.swift
git commit -m "feat(ios): open ghostcrab://pair deep links from the OS camera and Safari" -m "Registers the ghostcrab URL scheme (CFBundleURLTypes) and handles onOpenURL in RootView: the token goes to PairingHandoff, only the gateway URL is pushed as Route.manualEntry. Rejected links show an alert that never echoes the payload."
```

---

### Task 9: Confirm a model before activating it, with exactly one call per confirm

**Files:**
- Modify (rewrite): `ios/GhostCrab/UI/Models/ModelManagerViewModel.swift`
- Modify: `ios/GhostCrab/UI/Models/ModelManagerScreen.swift:26` (dialog), `:80-82` (tap)
- Test: `ios/GhostCrabTests/UI/ModelManagerViewModelTests.swift`

Audit correction: `setActive(_:)` already guards on `activatingId`, so a double tap never fired two calls. Two real defects remain. The confirm the frozen `ModelRepository` contract requires ("Confirmation dialog is the caller's responsibility") is missing. And a failed activation's error is wiped immediately, because `setActive` sets `lastError` and then calls `load()`, which sets `lastError = nil` on success.

- [ ] **Step 1: Write the failing tests**

Create `ios/GhostCrabTests/UI/ModelManagerViewModelTests.swift`:

```swift
import Testing
import Foundation
@testable import GhostCrab

@MainActor
@Suite("ModelManagerViewModel activation")
struct ModelManagerViewModelTests {

    private func makeVM(setActiveError: GatewayError? = nil) async -> (ModelManagerViewModel, FakeModelRepository) {
        let repo = FakeModelRepository(
            models: [FakeModelRepository.model("m1", active: true), FakeModelRepository.model("m2")],
            setActiveError: setActiveError
        )
        let vm = ModelManagerViewModel(models: repo)
        await vm.load()
        return (vm, repo)
    }

    @Test func tapOnInactiveModelAsksForConfirmation() async {
        let (vm, repo) = await makeVM()
        vm.requestActivation("m2")
        #expect(vm.pendingActivation?.id == "m2")
        #expect(repo.setActiveCalls.isEmpty)
    }

    @Test func tapOnActiveModelDoesNothing() async {
        let (vm, _) = await makeVM()
        vm.requestActivation("m1")
        #expect(vm.pendingActivation == nil)
    }

    @Test func cancelClearsPendingWithoutCallingGateway() async {
        let (vm, repo) = await makeVM()
        vm.requestActivation("m2")
        vm.cancelActivation()
        #expect(vm.pendingActivation == nil)
        #expect(repo.setActiveCalls.isEmpty)
    }

    @Test func doubleConfirmActivatesExactlyOnce() async throws {
        let (vm, repo) = await makeVM()
        vm.requestActivation("m2")
        let target = try #require(vm.pendingActivation)

        vm.confirmActivation(target)
        vm.confirmActivation(target)
        await vm.activationTask?.value

        #expect(repo.setActiveCalls == ["m2"])
        #expect(vm.models_.first(where: { $0.isActive })?.id == "m2")
        #expect(vm.pendingActivation == nil)
    }

    @Test func failedActivationKeepsTheErrorVisibleAfterReload() async throws {
        let (vm, repo) = await makeVM(
            setActiveError: .api(url: "http://192.168.1.20:18789/api/models/active", statusCode: 404)
        )
        vm.requestActivation("m2")
        let target = try #require(vm.pendingActivation)

        vm.confirmActivation(target)
        await vm.activationTask?.value

        #expect(repo.setActiveCalls == ["m2"])
        #expect(vm.lastError?.hasPrefix("Failed to activate M2") == true)
        #expect(vm.models_.first(where: { $0.isActive })?.id == "m1")
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

```bash
cd ios && xcodegen generate && xcodebuild test -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:GhostCrabTests/ModelManagerViewModelTests -quiet
```
Expected: the build fails with `value of type 'ModelManagerViewModel' has no member 'requestActivation'`, then `** TEST FAILED **`.

- [ ] **Step 3: Rewrite the ViewModel**

Replace the whole content of `ios/GhostCrab/UI/Models/ModelManagerViewModel.swift`:

```swift
import Foundation
import SwiftUI

/// ViewModel for the Model Manager screen.
///
/// Mirrors `ModelManagerViewModel.kt`. Activation has two steps, as the frozen
/// `ModelRepository.setActiveModel` contract requires ("Confirmation dialog is
/// the caller's responsibility"). A row tap stages ``pendingActivation`` (the
/// screen shows a confirmation dialog), and only ``confirmActivation(_:)``
/// talks to the gateway. A single tracked ``activationTask`` guarantees one
/// `setActiveModel` call per confirmation, however often the button is hit.
@MainActor
@Observable
public final class ModelManagerViewModel {

    // MARK: - Dependencies

    private let models: any ModelRepository

    // MARK: - Observable state

    /// Full list of models reported by the gateway.
    public private(set) var models_: [ModelInfo] = []

    /// Identifier of the model currently being activated, or `nil` when idle.
    /// Drives an inline `ProgressView` next to the row.
    public private(set) var activatingId: String?

    /// True while the initial load is in flight (before any models have been shown).
    public private(set) var isLoading: Bool = false

    /// True when the gateway returned 404 on `/models`, meaning this build of
    /// the gateway does not support the feature.
    public private(set) var notSupported: Bool = false

    /// Last error surfaced to the user. Includes URL + status code + exception
    /// class when the source is a `GatewayError`. `nil` when no error pending.
    public var lastError: String?

    /// Model the user tapped and must confirm before activation. Drives the dialog.
    public private(set) var pendingActivation: ModelInfo?

    /// In-flight activation. Read-only outside the VM so tests can await it.
    @ObservationIgnored private(set) var activationTask: Task<Void, Never>?

    // MARK: - Init

    public init(models: any ModelRepository) {
        self.models = models
    }

    // MARK: - Lifecycle

    /// Loads the model list. Call from the screen's `.task` modifier and from
    /// `.refreshable`.
    public func load() async {
        isLoading = models_.isEmpty
        defer { isLoading = false }
        do {
            let list = try await models.getModels()
            self.models_ = list
            self.notSupported = false
            self.lastError = nil
        } catch GatewayError.api(_, let statusCode, _) where statusCode == 404 {
            self.notSupported = true
            self.models_ = []
            self.lastError = nil
        } catch let error as GatewayError {
            self.lastError = describe(error)
        } catch {
            self.lastError = "\(type(of: error)): \(error.localizedDescription)"
        }
    }

    // MARK: - Activation (tap → confirm → activate)

    /// Row tap. Stages `id` for confirmation unless an activation is in flight
    /// or the model is already active.
    ///
    /// - Parameter id: The `ModelInfo.id` of the tapped row.
    public func requestActivation(_ id: String) {
        guard activationTask == nil, activatingId == nil else { return }
        guard let target = models_.first(where: { $0.id == id }), !target.isActive else { return }
        pendingActivation = target
    }

    /// Dialog cancel / dismiss. Never touches the gateway.
    public func cancelActivation() {
        pendingActivation = nil
    }

    /// Dialog confirm. Starts exactly one activation; repeat calls while it is
    /// in flight are ignored.
    ///
    /// Takes the model from the dialog's `presenting:` value rather than
    /// reading ``pendingActivation``, because SwiftUI may clear the dialog
    /// binding before it runs the button action.
    ///
    /// - Parameter model: The model shown in the dialog.
    public func confirmActivation(_ model: ModelInfo) {
        pendingActivation = nil
        guard activationTask == nil, activatingId == nil else { return }
        activationTask = Task {
            await self.setActive(model.id)
            self.activationTask = nil
        }
    }

    /// Sets the given model as active, then reloads so the checkmark follows
    /// the gateway's state. On failure the list is reloaded first and the
    /// error is set afterwards, because `load()` clears ``lastError`` on success.
    ///
    /// - Parameter id: The `ModelInfo.id` of the model to activate.
    public func setActive(_ id: String) async {
        guard activatingId == nil else { return }
        guard let target = models_.first(where: { $0.id == id }) else { return }
        if target.isActive { return }

        activatingId = id
        defer { activatingId = nil }
        do {
            try await models.setActiveModel(modelId: id)
            await load()
        } catch let error as GatewayError {
            await load()
            self.lastError = "Failed to activate \(target.displayName) \u{2014} \(describe(error))"
        } catch {
            await load()
            self.lastError = "Failed to activate \(target.displayName) \u{2014} \(type(of: error)): \(error.localizedDescription)"
        }
    }

    // MARK: - Helpers

    private func describe(_ error: GatewayError) -> String {
        let typeName = String(describing: type(of: error))
        return "\(typeName): \(error.localizedDescription)"
    }
}
```

- [ ] **Step 4: Update the screen**

In `ios/GhostCrab/UI/Models/ModelManagerScreen.swift`, replace the row tap (lines 80-82, `Task { await vm.setActive(model.id) }`):

```swift
                        .onTapGesture {
                            vm.requestActivation(model.id)
                        }
```

Replace `.task { await vm.load() }` (line 26) with:

```swift
        .task { await vm.load() }
        .confirmationDialog(
            "Activate model?",
            isPresented: Binding(
                get: { vm.pendingActivation != nil },
                set: { if !$0 { vm.cancelActivation() } }
            ),
            titleVisibility: .visible,
            presenting: vm.pendingActivation
        ) { model in
            Button("Activate \(model.displayName)") { vm.confirmActivation(model) }
            Button("Cancel", role: .cancel) { vm.cancelActivation() }
        } message: { model in
            Text("New requests on this gateway will use \(model.displayName) (\(model.provider)).")
        }
```

- [ ] **Step 5: Run the tests to verify they pass** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

```bash
cd ios && xcodegen generate && xcodebuild test -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:GhostCrabTests/ModelManagerViewModelTests -quiet
```
Expected: `** TEST SUCCEEDED **`.

- [ ] **Step 6: Commit**

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add ios/GhostCrab/UI/Models/ModelManagerViewModel.swift ios/GhostCrab/UI/Models/ModelManagerScreen.swift ios/GhostCrabTests/UI/ModelManagerViewModelTests.swift
git commit -m "feat(ios): confirm before activating a model" -m "Row tap stages pendingActivation and shows a confirmationDialog; confirmActivation runs one tracked task, so repeat taps cannot fire a second setActiveModel. Also fixes a failed activation's error being cleared by the follow-up reload."
```

---

### Task 10: Bundle Inter and JetBrains Mono

**Files:**
- Modify: `ios/GhostCrab/UI/Theme/Fonts.swift` (whole file)
- Add: `ios/GhostCrab/Resources/Fonts/Inter-Regular.ttf`, `Inter-Medium.ttf`, `Inter-SemiBold.ttf`, `Inter-Bold.ttf`, `JetBrainsMono-Regular.ttf`, `JetBrainsMono-Medium.ttf`, `LICENSE-Inter.txt`, `LICENSE-JetBrainsMono.txt`
- Modify: `ios/project.yml` (`UIAppFonts`)
- Test: `ios/GhostCrabTests/Theme/FontRegistrationTests.swift`

`Fonts.swift` looks up the PostScript names `Inter-Regular`, `Inter-Medium`, `Inter-SemiBold`, `Inter-Bold`, `JetBrainsMono-Regular` and `JetBrainsMono-Medium`. The upstream static TTFs use those same names as file names. No new `sources:` entry is needed: `targets.GhostCrab.sources` already includes the whole `GhostCrab/` folder (project.yml:25-28), so XcodeGen copies the `.ttf` and `.txt` files to the bundle root as resources. Adding a second path would produce duplicate build files.

- [ ] **Step 1: Write the failing test**

Create `ios/GhostCrabTests/Theme/FontRegistrationTests.swift`:

```swift
import Testing
import UIKit
@testable import GhostCrab

/// SwiftUI's `Font.custom` silently falls back to the system font when a face
/// is missing, so typography regressions are invisible without this check.
/// Runs inside the host app, so it sees exactly what `UIAppFonts` registered.
@Suite("Bundled fonts")
struct FontRegistrationTests {

    @Test(arguments: AppFont.PostScriptName.all)
    func fontIsRegistered(name: String) {
        #expect(
            UIFont(name: name, size: 12) != nil,
            "\(name) is not registered. Check UIAppFonts in project.yml and Resources/Fonts/\(name).ttf"
        )
    }
}
```

- [ ] **Step 2: Name the PostScript constants**

Replace the whole content of `ios/GhostCrab/UI/Theme/Fonts.swift`:

```swift
import SwiftUI

/// GhostCrab font ramp: wraps the bundled Inter and JetBrains Mono families.
///
/// Mirrors `Type.kt`. The faces ship as static TTFs in `Resources/Fonts/` and
/// are registered through `UIAppFonts` in `project.yml`. ``PostScriptName``
/// holds the exact names `Font.custom` looks up, and each file is named
/// `<PostScriptName>.ttf`. A missing or renamed file makes SwiftUI fall back
/// to the system font silently; `FontRegistrationTests` guards against that.
///
/// All UI text uses `body`/`bodyMedium`/`bodyBold`/`bodyBlack`. IP addresses,
/// ports, JSON, CLI output, and token fields use `mono`/`monoMedium`.
enum AppFont {

    /// PostScript names of the bundled faces.
    enum PostScriptName {
        static let interRegular = "Inter-Regular"
        static let interMedium = "Inter-Medium"
        static let interSemiBold = "Inter-SemiBold"
        static let interBold = "Inter-Bold"
        static let jetBrainsMonoRegular = "JetBrainsMono-Regular"
        static let jetBrainsMonoMedium = "JetBrainsMono-Medium"

        /// Every bundled face; must match `UIAppFonts` in `project.yml`.
        static let all: [String] = [
            interRegular, interMedium, interSemiBold, interBold,
            jetBrainsMonoRegular, jetBrainsMonoMedium,
        ]
    }

    // MARK: - Inter (UI text)

    /// Inter Regular: body copy, labels, default UI text.
    static func body(_ size: CGFloat) -> Font {
        .custom(PostScriptName.interRegular, size: size)
    }

    /// Inter Medium: subdued titles, label-medium.
    static func bodyMedium(_ size: CGFloat) -> Font {
        .custom(PostScriptName.interMedium, size: size)
    }

    /// Inter SemiBold: headlines, prominent titles.
    static func bodyBold(_ size: CGFloat) -> Font {
        .custom(PostScriptName.interSemiBold, size: size)
    }

    /// Inter Bold: display-scale text.
    static func bodyBlack(_ size: CGFloat) -> Font {
        .custom(PostScriptName.interBold, size: size)
    }

    // MARK: - JetBrains Mono (code, IPs, JSON, tokens)

    /// JetBrains Mono Regular: IP addresses, ports, JSON, CLI output.
    static func mono(_ size: CGFloat) -> Font {
        .custom(PostScriptName.jetBrainsMonoRegular, size: size)
    }

    /// JetBrains Mono Medium: emphasised code tokens.
    static func monoMedium(_ size: CGFloat) -> Font {
        .custom(PostScriptName.jetBrainsMonoMedium, size: size)
    }
}
```

- [ ] **Step 3: Run the test to verify it fails** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

```bash
cd ios && xcodegen generate && xcodebuild test -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:GhostCrabTests/FontRegistrationTests -quiet
```
Expected: `** TEST FAILED **`, with 6 issues like `Inter-Regular is not registered.`

- [ ] **Step 4: Download the fonts and licenses** ⚠ manual (any machine with `curl` and `unzip`; run from the repo root)

Both families are SIL Open Font License 1.1. The license must ship with the fonts, so it is copied next to them and bundled.

```bash
REPO="$(git rev-parse --show-toplevel)"
FONTS="$REPO/ios/GhostCrab/Resources/Fonts"
mkdir -p "$FONTS" /tmp/gc-fonts && cd /tmp/gc-fonts
curl -fL -o inter.zip https://github.com/rsms/inter/releases/download/v4.1/Inter-4.1.zip
curl -fL -o jbmono.zip https://github.com/JetBrains/JetBrainsMono/releases/download/v2.304/JetBrainsMono-2.304.zip
unzip -o -q inter.zip -d inter
unzip -o -q jbmono.zip -d jbmono
cp inter/extras/ttf/Inter-Regular.ttf inter/extras/ttf/Inter-Medium.ttf inter/extras/ttf/Inter-SemiBold.ttf inter/extras/ttf/Inter-Bold.ttf "$FONTS/"
cp jbmono/fonts/ttf/JetBrainsMono-Regular.ttf jbmono/fonts/ttf/JetBrainsMono-Medium.ttf "$FONTS/"
cp inter/LICENSE.txt "$FONTS/LICENSE-Inter.txt"
cp jbmono/OFL.txt "$FONTS/LICENSE-JetBrainsMono.txt"
ls "$FONTS"
```
Expected listing: `Inter-Bold.ttf Inter-Medium.ttf Inter-Regular.ttf Inter-SemiBold.ttf JetBrainsMono-Medium.ttf JetBrainsMono-Regular.ttf LICENSE-Inter.txt LICENSE-JetBrainsMono.txt`.
If an archive layout has changed upstream, find the static files with `find inter jbmono -name '*.ttf' | grep -E 'Inter-(Regular|Medium|SemiBold|Bold)\.ttf|JetBrainsMono-(Regular|Medium)\.ttf'`. Never use `InterVariable.ttf`: its PostScript names differ.

- [ ] **Step 5: Register the fonts**

In `ios/project.yml`, under `targets.GhostCrab.info.properties`, insert after the `LSApplicationCategoryType` line:

```yaml
        UIAppFonts:
          - Inter-Regular.ttf
          - Inter-Medium.ttf
          - Inter-SemiBold.ttf
          - Inter-Bold.ttf
          - JetBrainsMono-Regular.ttf
          - JetBrainsMono-Medium.ttf
```

- [ ] **Step 6: Run the test to verify it passes** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

```bash
cd ios && xcodegen generate && xcodebuild test -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:GhostCrabTests/FontRegistrationTests -quiet
```
Expected: `** TEST SUCCEEDED **` (6 arguments passed).

- [ ] **Step 7: Visual check** ⚠ manual (simulator)

Launch the app. Expected: onboarding text renders in Inter (a double-storey `a`, not SF), and Manual Entry's assembled URL renders in JetBrains Mono.

- [ ] **Step 8: Commit**

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add ios/GhostCrab/UI/Theme/Fonts.swift ios/GhostCrab/Resources/Fonts ios/project.yml ios/GhostCrabTests/Theme/FontRegistrationTests.swift
git commit -m "feat(ios): bundle Inter and JetBrains Mono" -m "Adds the six static TTFs Fonts.swift references (Inter 4.1, JetBrains Mono 2.304) plus their OFL licenses, registers them via UIAppFonts, and adds a hosted test that fails if any face silently falls back to the system font."
```

---

### Task 11: Privacy manifest

**Files:**
- Create: `ios/GhostCrab/Resources/PrivacyInfo.xcprivacy`
- Modify: `ios/project.yml` (`targets.GhostCrab.sources`)
- Test: `ios/GhostCrabTests/App/PrivacyManifestTests.swift`

What goes in the manifest, with evidence:
- **UserDefaults: included, reason `CA92.1`** (access info from the same app). Evidence: `Data/Storage/SettingsStore.swift:24-28` (`UserDefaults`, default `.standard`; settings + onboarding keys) and `Data/Storage/ProfileStore.swift:26,35,154,163` (profile metadata blob). Task 13 keeps that data in UserDefaults.
- **File timestamp, system boot time, disk space, active keyboards: excluded.** Step 1's sweep finds no use of `attributesOfItem`, `modificationDate`/`creationDate`/`NSFileModificationDate`, `systemUptime`/`mach_absolute_time`, volume-capacity APIs, `statfs` or `activeInputModes` anywhere in `ios/GhostCrab`.
- **Tracking: `false`; tracking domains and collected data types: empty.** The app has no SDKs, analytics or ads. Gateway traffic goes to the user's own server, which is not data the developer or third parties collect.

- [ ] **Step 1: Re-run the required-reason API sweep** (runs anywhere)

```bash
grep -rnE "attributesOfItem|modificationDate|creationDate|NSFileModificationDate|NSFileCreationDate|contentModificationDateKey|creationDateKey|systemUptime|mach_absolute_time|volumeAvailableCapacity|systemFreeSize|systemSize|statfs|activeInputModes|UITextInputMode" ios/GhostCrab; echo "exit=$?"
```
Expected: no matches, `exit=1`. If anything matches, add that category with its approved reason before continuing.

- [ ] **Step 2: Write the failing test**

Create `ios/GhostCrabTests/App/PrivacyManifestTests.swift`:

```swift
import Testing
import Foundation
@testable import GhostCrab

/// App Store Connect rejects uploads that use required-reason APIs without a
/// manifest (ITMS-91053). Runs in the host app, so `Bundle.main` is GhostCrab.app.
@Suite("Privacy manifest")
struct PrivacyManifestTests {

    @Test func manifestIsBundledAndDeclaresOnlyUserDefaults() throws {
        let url = try #require(Bundle.main.url(forResource: "PrivacyInfo", withExtension: "xcprivacy"))
        let data = try Data(contentsOf: url)
        let raw = try PropertyListSerialization.propertyList(from: data, format: nil)
        let plist = try #require(raw as? [String: Any])

        #expect(plist["NSPrivacyTracking"] as? Bool == false)
        #expect((plist["NSPrivacyTrackingDomains"] as? [Any])?.isEmpty == true)
        #expect((plist["NSPrivacyCollectedDataTypes"] as? [Any])?.isEmpty == true)

        let apis = try #require(plist["NSPrivacyAccessedAPITypes"] as? [[String: Any]])
        #expect(apis.count == 1)
        #expect(apis.first?["NSPrivacyAccessedAPIType"] as? String == "NSPrivacyAccessedAPICategoryUserDefaults")
        #expect(apis.first?["NSPrivacyAccessedAPITypeReasons"] as? [String] == ["CA92.1"])
    }
}
```

- [ ] **Step 3: Run the test to verify it fails** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

```bash
cd ios && xcodegen generate && xcodebuild test -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:GhostCrabTests/PrivacyManifestTests -quiet
```
Expected: `** TEST FAILED **`, with `#require` failing on `Bundle.main.url(forResource: "PrivacyInfo", …)`.

- [ ] **Step 4: Create the manifest**

Create `ios/GhostCrab/Resources/PrivacyInfo.xcprivacy`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>NSPrivacyTracking</key>
    <false/>
    <key>NSPrivacyTrackingDomains</key>
    <array/>
    <key>NSPrivacyCollectedDataTypes</key>
    <array/>
    <key>NSPrivacyAccessedAPITypes</key>
    <array>
        <dict>
            <key>NSPrivacyAccessedAPIType</key>
            <string>NSPrivacyAccessedAPICategoryUserDefaults</string>
            <key>NSPrivacyAccessedAPITypeReasons</key>
            <array>
                <string>CA92.1</string>
            </array>
        </dict>
    </array>
</dict>
</plist>
```

- [ ] **Step 5: Add it to the resources phase explicitly**

Do not rely on XcodeGen's file-type detection for `.xcprivacy`, which varies by version. In `ios/project.yml`, replace `targets.GhostCrab.sources`:

```yaml
    sources:
      - path: GhostCrab
        excludes:
          - "**/.DS_Store"
          - "Resources/PrivacyInfo.xcprivacy"
      - path: GhostCrab/Resources/PrivacyInfo.xcprivacy
        buildPhase: resources
```

- [ ] **Step 6: Lint and run the test** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

```bash
cd ios && plutil -lint GhostCrab/Resources/PrivacyInfo.xcprivacy && xcodegen generate && xcodebuild test -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:GhostCrabTests/PrivacyManifestTests -quiet
```
Expected: `GhostCrab/Resources/PrivacyInfo.xcprivacy: OK`, then `** TEST SUCCEEDED **`.

- [ ] **Step 7: Commit**

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add ios/GhostCrab/Resources/PrivacyInfo.xcprivacy ios/project.yml ios/GhostCrabTests/App/PrivacyManifestTests.swift
git commit -m "feat(ios): add PrivacyInfo.xcprivacy" -m "Declares no tracking, no collected data and one required-reason API: UserDefaults (CA92.1) used by SettingsStore and ProfileStore. A sweep found no file-timestamp, boot-time, disk-space or keyboard APIs. Hosted test asserts the manifest ships in the bundle."
```

---

### Task 12: ATS: local networking only, an ADR, and honest UI copy

**Files:**
- Modify: `ios/project.yml` (`NSAppTransportSecurity`)
- Create: `ios/GhostCrab/Domain/Util/ATSCleartextPolicy.swift`
- Modify: `ios/GhostCrab/UI/Connection/ManualEntryViewModel.swift` (derived property)
- Modify: `ios/GhostCrab/UI/Connection/ManualEntryScreen.swift:66-70` (hint)
- Modify: `ios/GhostCrab/UI/Settings/SettingsScreen.swift:100` (footer)
- Create: `docs/adr/0006-ios-ats-local-networking.md`
- Modify: `docs/IMPLEMENTATION_PLAN.md` (§7 ADR list)
- Test: `ios/GhostCrabTests/Domain/ATSCleartextPolicyTests.swift`, `ios/GhostCrabTests/UI/ManualEntryViewModelTests.swift`

ADR number: `docs/IMPLEMENTATION_PLAN.md` §7 reserves ADR-005 for "Onboarding does not execute privileged actions on user machines", so this ADR is **0006**.

- [ ] **Step 1: Write the failing tests**

Create `ios/GhostCrabTests/Domain/ATSCleartextPolicyTests.swift`:

```swift
import Testing
@testable import GhostCrab

@Suite("ATS cleartext prediction")
struct ATSCleartextPolicyTests {

    @Test(arguments: ["192.168.1.20", "8.8.8.8", "fe80::1", "[fe80::1]", "nas", "localhost", "gateway.local", "Gateway.LOCAL", ""])
    func exemptHostsAreNotBlocked(host: String) {
        #expect(!ATSCleartextPolicy.blocksCleartext(host: host))
    }

    @Test(arguments: ["gw.example.com", "box.tailnet-1234.ts.net", "gateway.home.arpa", "host.corp."])
    func qualifiedHostnamesAreBlocked(host: String) {
        #expect(ATSCleartextPolicy.blocksCleartext(host: host))
    }
}
```

Add inside `struct ManualEntryViewModelTests` (`ios/GhostCrabTests/UI/ManualEntryViewModelTests.swift`), after the last test:

```swift
    @Test func warnsWhenATSWillBlockPlainHTTPToAHostname() {
        let vm = makeVM(handoff: PairingHandoff())
        vm.setHost("box.tailnet-1234.ts.net")
        #expect(vm.cleartextBlockedByATS)

        vm.useHttps = true
        #expect(!vm.cleartextBlockedByATS)

        vm.useHttps = false
        vm.setHost("192.168.1.20")
        #expect(!vm.cleartextBlockedByATS)
    }
```

- [ ] **Step 2: Run the tests to verify they fail** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

```bash
cd ios && xcodegen generate && xcodebuild test -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:GhostCrabTests/ATSCleartextPolicyTests -only-testing:GhostCrabTests/ManualEntryViewModelTests -quiet
```
Expected: the build fails with `cannot find 'ATSCleartextPolicy' in scope`, then `** TEST FAILED **`.

- [ ] **Step 3: Implement the policy and the VM property**

Create `ios/GhostCrab/Domain/Util/ATSCleartextPolicy.swift`:

```swift
import Foundation

/// Predicts whether iOS App Transport Security refuses a plain-HTTP load to
/// `host` before any app code, ``CleartextPublicIPGuard`` included, runs.
///
/// ATS exempts IP literals, unqualified host names (no dot) and `.local`
/// names. Every other name needs HTTPS: public FQDNs, Tailscale MagicDNS
/// `*.ts.net`, split-DNS and `home.arpa` names. The Settings cleartext toggle
/// cannot change this. See `docs/adr/0006-ios-ats-local-networking.md`.
public enum ATSCleartextPolicy {

    /// - Parameter rawHost: Host as typed (case, surrounding whitespace and IPv6 brackets are tolerated).
    /// - Returns: `true` if `http://<host>` will fail with `NSURLErrorAppTransportSecurityRequiresSecureConnection`.
    public static func blocksCleartext(host rawHost: String) -> Bool {
        var host = rawHost.trimmingCharacters(in: .whitespaces).lowercased()
        if host.hasPrefix("[") && host.hasSuffix("]") {
            host = String(host.dropFirst().dropLast())
        }
        if host.hasSuffix(".") {
            host = String(host.dropLast())
        }
        guard !host.isEmpty else { return false }
        if host.contains(":") { return false }                                          // IPv6 literal
        if host.allSatisfy({ $0.isASCII && ($0.isNumber || $0 == ".") }) { return false } // IPv4 literal
        if !host.contains(".") { return false }                                         // unqualified name
        if host.hasSuffix(".local") { return false }                                    // mDNS / Bonjour
        return true
    }
}
```

In `ios/GhostCrab/UI/Connection/ManualEntryViewModel.swift`, add under the `assembledURL` property (in `// MARK: - Derived`):

```swift

    /// `true` when HTTPS is off and iOS ATS will refuse plain HTTP to ``host``
    /// (a qualified hostname that is neither an IP literal nor `.local`).
    /// The screen shows a warning; connecting would fail before the request leaves the device.
    public var cleartextBlockedByATS: Bool {
        !useHttps && ATSCleartextPolicy.blocksCleartext(host: host)
    }
```

- [ ] **Step 4: Show the hint on Manual Entry and fix the Settings footer**

In `ios/GhostCrab/UI/Connection/ManualEntryScreen.swift`, insert right after the host-error block (lines 66-70, `if let err = vm.hostError { … }`) and inside the same `VStack`:

```swift
                        if vm.cleartextBlockedByATS {
                            Text("iOS blocks plain HTTP to internet hostnames (App Transport Security). Turn on HTTPS, or use the gateway's IP address or .local name.")
                                .font(AppFont.body(12))
                                .foregroundStyle(DesignTokens.Color.amberWarn)
                        }
```

In `ios/GhostCrab/UI/Settings/SettingsScreen.swift`, replace the footer `Text("Disables the cleartext-IP guard …")` (line 100) with:

```swift
            Text("Allows HTTP to public IP addresses; loopback, link-local and RFC-1918 ranges are always allowed. It cannot unlock HTTP to hostnames such as gw.example.com or *.ts.net: iOS App Transport Security blocks those regardless, so use HTTPS. Leave this off unless you knowingly connect to a public-IP gateway over HTTP.")
```

- [ ] **Step 5: Add the ATS key**

In `ios/project.yml`, under `targets.GhostCrab.info.properties`, insert after `ITSAppUsesNonExemptEncryption: false`:

```yaml
        NSAppTransportSecurity:
          NSAllowsLocalNetworking: true
```

- [ ] **Step 6: Write the ADR and list it**

Create `docs/adr/0006-ios-ats-local-networking.md`:

```markdown
# ADR-006: iOS App Transport Security: Local Networking Only, No Arbitrary Loads

**Status:** Accepted
**Date:** 2026-10-13

> ADR-005 is reserved in `docs/IMPLEMENTATION_PLAN.md` §7 ("Onboarding does not execute privileged actions on user machines"), so this decision is 006.

## Context

GhostCrab talks to self-hosted OpenClaw gateways, often over plain HTTP on a LAN (`http://192.168.x.y:18789`, `http://gateway.local:18789`). Android permits cleartext everywhere (`network_security_config.xml` base-config) and gates only *public IP literals* with `CleartextPublicIpInterceptor` plus a user toggle. iOS ports that as `CleartextPublicIPGuard`, a `URLProtocol`.

On iOS, App Transport Security (ATS) runs before any app networking code. With no exceptions configured, ATS:

- allows plain HTTP to IP literals, unqualified host names (`http://nas:18789`) and `.local` names;
- blocks plain HTTP to every qualified domain name: public DNS names, Tailscale MagicDNS (`*.ts.net`), split-horizon and `home.arpa` names. It fails with `NSURLErrorAppTransportSecurityRequiresSecureConnection` (-1022), and `OpenClawAPIClient` surfaces that as `GatewayError.unreachable`.

## Decision

1. `Info.plist` declares `NSAppTransportSecurity = { NSAllowsLocalNetworking = YES }` and nothing else. On iOS 10+ the OS already exempts local hosts, so the key changes no behaviour today. It records the intent in the binary and keeps the local exemption explicit if other ATS keys are ever added.
2. `NSAllowsArbitraryLoads` and per-domain `NSExceptionAllowsInsecureHTTPLoads` are rejected. They turn ATS off for every host, need an App Review justification, and are unnecessary: a gateway can always be fronted with TLS (Cloudflare tunnel per `docs/TUNNEL_SETUP.md`, or Tailscale HTTPS certificates).
3. Plain HTTP to a qualified hostname is unsupported on iOS. The Settings toggle "Allow HTTP to public IPs" affects only `CleartextPublicIPGuard` (IP literals) and cannot override ATS. Its footer copy says so.
4. Manual Entry warns before connecting when `ATSCleartextPolicy.blocksCleartext(host:)` predicts ATS will refuse the load.

## Consequences

- Platform divergence: Android can reach `http://box.tailnet.ts.net:18789`; iOS cannot. iOS users must use HTTPS, the IP literal, or the unqualified / `.local` name.
- `CleartextPublicIPGuard` stays the only app-level cleartext policy, and it only classifies IP literals (`IPLiteralTests`).
- ATS exceptions are static `Info.plist` keys, so there is no way to add a user-chosen per-host exception at runtime. Revisit only if Apple changes that or App Review guidance changes.
```

In `docs/IMPLEMENTATION_PLAN.md` §7, add after the `- ADR-005 — Onboarding does not execute privileged actions on user machines` line:

```markdown
- ADR-006 — iOS App Transport Security: local networking only, no arbitrary loads (written 2026-10-13)
```

- [ ] **Step 7: Run the tests and check the plist** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

```bash
cd ios && xcodegen generate && plutil -extract NSAppTransportSecurity.NSAllowsLocalNetworking raw GhostCrab/Resources/Info.plist && xcodebuild test -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:GhostCrabTests/ATSCleartextPolicyTests -only-testing:GhostCrabTests/ManualEntryViewModelTests -quiet
```
Expected: `true`, then `** TEST SUCCEEDED **`.

- [ ] **Step 8: Commit**

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add ios/project.yml ios/GhostCrab/Domain/Util/ATSCleartextPolicy.swift ios/GhostCrab/UI/Connection/ManualEntryViewModel.swift ios/GhostCrab/UI/Connection/ManualEntryScreen.swift ios/GhostCrab/UI/Settings/SettingsScreen.swift ios/GhostCrabTests/Domain/ATSCleartextPolicyTests.swift ios/GhostCrabTests/UI/ManualEntryViewModelTests.swift docs/adr/0006-ios-ats-local-networking.md docs/IMPLEMENTATION_PLAN.md
git commit -m "feat(ios): declare ATS local networking and explain hostname HTTP limits" -m "Adds NSAllowsLocalNetworking (no arbitrary loads), ADR-006, a Manual Entry warning when ATS will block plain HTTP to a qualified hostname, and Settings copy stating the cleartext toggle only covers IP literals."
```

---

### Task 13: `ProfileStore` never overwrites a profile blob it could not read

**Files:**
- Modify: `ios/GhostCrab/Data/Storage/Keychain.swift` (append `TokenStoring`)
- Modify: `ios/GhostCrab/Data/Storage/ProfileStore.swift` (error type, init, `save`, `delete`, loaders)
- Create: `ios/GhostCrabTests/Support/InMemoryTokenStore.swift`
- Test: `ios/GhostCrabTests/Storage/ProfileStoreTests.swift`

Audit correction: the profile list is a `UserDefaults` blob (`com.qavren.ghostcrab.profiles`), not a file. "Back up the bad file" therefore means copying the blob to a timestamped UserDefaults key. Behaviour after this task: reads (`observeAll`, `getAll`) log and show an empty list without mutating anything. The first write (`save`/`delete`) quarantines the blob (copies it to the backup key, then removes the live key) and throws `ProfileStoreError.corruptProfileData(backupKey:)`. A retry then starts clean, and the old data is still recoverable. The new `TokenStoring` protocol is a seam that lets tests fake the Keychain.

- [ ] **Step 1: Add the token-store seam**

Append to `ios/GhostCrab/Data/Storage/Keychain.swift`:

```swift

// MARK: - Seam

/// Token persistence used by ``ProfileStore``. ``Keychain`` is the production
/// conformer; tests inject an in-memory fake.
public protocol TokenStoring: Sendable {
    /// Stores `token` for `profileId`, replacing any existing value.
    func setToken(_ token: String, for profileId: String) throws
    /// Returns the token for `profileId`, or `nil` if none exists.
    func getToken(for profileId: String) throws -> String?
    /// Deletes the token for `profileId`. No-op if none exists.
    func deleteToken(for profileId: String) throws
}

extension Keychain: TokenStoring {}
```

Create `ios/GhostCrabTests/Support/InMemoryTokenStore.swift`:

```swift
import Foundation
import Security
@testable import GhostCrab

/// `TokenStoring` fake. Failures can be switched on mid-test to simulate a
/// Keychain that can no longer read or delete (e.g. after a device restore).
final class InMemoryTokenStore: TokenStoring, @unchecked Sendable {
    private let lock = NSLock()
    private var tokens: [String: String]
    private var failReads = false
    private var failDeletes = false

    init(tokens: [String: String] = [:]) {
        self.tokens = tokens
    }

    func simulateFailures(reads: Bool, deletes: Bool) {
        lock.withLock {
            failReads = reads
            failDeletes = deletes
        }
    }

    func token(for profileId: String) -> String? {
        lock.withLock { tokens[profileId] }
    }

    func setToken(_ token: String, for profileId: String) throws {
        lock.withLock { tokens[profileId] = token }
    }

    func getToken(for profileId: String) throws -> String? {
        let (shouldFail, value) = lock.withLock { (failReads, tokens[profileId]) }
        if shouldFail { throw KeychainError.unexpectedStatus(errSecDecode) }
        return value
    }

    func deleteToken(for profileId: String) throws {
        let shouldFail = lock.withLock { failDeletes }
        if shouldFail { throw KeychainError.unexpectedStatus(errSecInteractionNotAllowed) }
        lock.withLock { tokens[profileId] = nil }
    }
}
```

- [ ] **Step 2: Write the failing tests**

Create `ios/GhostCrabTests/Storage/ProfileStoreTests.swift`:

```swift
import Testing
import Foundation
@testable import GhostCrab

@Suite("ProfileStore corrupt-data handling")
struct ProfileStoreTests {

    private func makeDefaults() throws -> UserDefaults {
        try #require(UserDefaults(suiteName: "ProfileStoreTests.\(UUID().uuidString)"))
    }

    private func makeProfile() -> ConnectionProfile {
        ConnectionProfile(id: UUID(), displayName: "Basement", url: "http://192.168.1.20:18789", lastConnectedAt: nil, hasToken: false)
    }

    @Test func corruptBlobIsQuarantinedAndFirstWriteThrows() async throws {
        let defaults = try makeDefaults()
        let garbage = Data("not json".utf8)
        defaults.set(garbage, forKey: ProfileStore.profilesKey)
        let store = ProfileStore(defaults: defaults, keychain: InMemoryTokenStore())
        let profile = makeProfile()

        await #expect(throws: ProfileStoreError.self) {
            try await store.save(profile, token: nil)
        }

        let backupKeys = defaults.dictionaryRepresentation().keys.filter {
            $0.hasPrefix(ProfileStore.corruptBackupKeyPrefix)
        }
        #expect(backupKeys.count == 1)
        let backupKey = try #require(backupKeys.first)
        #expect(defaults.data(forKey: backupKey) == garbage)
        #expect(defaults.data(forKey: ProfileStore.profilesKey) == nil)

        // The retry starts clean; the old blob stays recoverable under backupKey.
        try await store.save(profile, token: nil)
        #expect(await store.getAll() == [profile])
    }

    @Test func corruptBlobReadsAsEmptyWithoutMutatingStorage() async throws {
        let defaults = try makeDefaults()
        let garbage = Data("not json".utf8)
        defaults.set(garbage, forKey: ProfileStore.profilesKey)
        let store = ProfileStore(defaults: defaults, keychain: InMemoryTokenStore())

        #expect(await store.getAll().isEmpty)
        #expect(defaults.data(forKey: ProfileStore.profilesKey) == garbage)
    }

    @Test func roundTripsProfilesAndTokens() async throws {
        let tokens = InMemoryTokenStore()
        let store = ProfileStore(defaults: try makeDefaults(), keychain: tokens)
        let profile = makeProfile()

        try await store.save(profile, token: "tok_123")

        #expect(await store.getAll() == [profile])
        #expect(try store.getToken(for: profile.id.uuidString) == "tok_123")
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

```bash
cd ios && xcodegen generate && xcodebuild test -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:GhostCrabTests/ProfileStoreTests -quiet
```
Expected: the build fails with `cannot find 'ProfileStoreError' in scope` and with `cannot convert value of type 'InMemoryTokenStore' to expected argument type 'Keychain'`, then `** TEST FAILED **`.

- [ ] **Step 4: Implement the quarantine**

In `ios/GhostCrab/Data/Storage/ProfileStore.swift`, insert above `public actor ProfileStore {`:

```swift
/// Errors raised by ``ProfileStore`` writes.
public enum ProfileStoreError: Error, Equatable, Sendable {
    /// The stored profile blob could not be decoded. It was copied to
    /// `backupKey` and removed from the live key, so a retry starts clean
    /// without destroying the unreadable data.
    case corruptProfileData(backupKey: String)
}

extension ProfileStoreError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .corruptProfileData(let backupKey):
            return "Saved gateway list was unreadable and has been backed up (\(backupKey)). Try again to save this gateway."
        }
    }
}

```

Inside the actor, add below `changedNotification`:

```swift

    /// Prefix of the UserDefaults key that holds a quarantined, unreadable
    /// profile blob (`<prefix><unix-ms>`).
    public static let corruptBackupKeyPrefix: String = "com.qavren.ghostcrab.profiles.corrupt."

    private static let log = PrivacySafeLogger(category: "profiles")
```

Change the keychain property and the init parameter (lines 27 and 34-37):

```swift
    private let keychain: any TokenStoring
```
```swift
    /// - Parameters:
    ///   - defaults: Backing `UserDefaults` store. Defaults to `.standard`.
    ///   - keychain: Token storage. Defaults to a fresh ``Keychain`` instance.
    public init(
        defaults: UserDefaults = .standard,
        keychain: any TokenStoring = Keychain()
    ) {
```

In `save(_:token:)`, replace `var current = loadProfiles()` with `var current = try loadProfilesForWrite()`, and add to its doc comment:

```swift
    /// - Throws: ``ProfileStoreError/corruptProfileData(backupKey:)`` if the
    ///   stored list could not be decoded. Nothing is written; the bad blob is
    ///   backed up and cleared, so retrying succeeds.
```

In `delete(id:)`, replace `var current = loadProfiles()` with `var current = try loadProfilesForWrite()`, and add the same `- Throws:` line to its doc comment.

Replace `loadProfilesNonIsolated()` (lines 151-158) with:

```swift
    /// Read path for observation and queries. Never mutates storage: on an
    /// undecodable blob it logs and returns `[]`; the next write quarantines it.
    /// `UserDefaults` is itself thread-safe and the decoder is value-typed.
    private nonisolated func loadProfilesNonIsolated() -> [ConnectionProfile] {
        guard let data = defaults.data(forKey: Self.profilesKey) else {
            return []
        }
        do {
            return try decoder.decode([ConnectionProfile].self, from: data)
        } catch {
            Self.log.error("Stored profile list is unreadable; showing none until the next write backs it up", error: error)
            return []
        }
    }

    /// Read path for mutations. An undecodable blob is copied to a timestamped
    /// backup key, the live key is removed, and the write is refused, so a
    /// write never silently replaces data it could not read.
    ///
    /// - Throws: ``ProfileStoreError/corruptProfileData(backupKey:)``.
    private func loadProfilesForWrite() throws -> [ConnectionProfile] {
        guard let data = defaults.data(forKey: Self.profilesKey) else {
            return []
        }
        do {
            return try decoder.decode([ConnectionProfile].self, from: data)
        } catch {
            let backupKey = Self.corruptBackupKeyPrefix + String(Int(Date().timeIntervalSince1970 * 1000))
            defaults.set(data, forKey: backupKey)
            defaults.removeObject(forKey: Self.profilesKey)
            Self.log.error("Quarantined unreadable profile list to \(backupKey)", error: error)
            throw ProfileStoreError.corruptProfileData(backupKey: backupKey)
        }
    }
```

Leave `loadProfiles()` (used by `getAll()` and `get(id:)`) in place. It keeps delegating to `loadProfilesNonIsolated()`.

- [ ] **Step 5: Run the tests to verify they pass** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

```bash
cd ios && xcodegen generate && xcodebuild test -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:GhostCrabTests/ProfileStoreTests -quiet
```
Expected: `** TEST SUCCEEDED **`.

- [ ] **Step 6: Commit**

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add ios/GhostCrab/Data/Storage/Keychain.swift ios/GhostCrab/Data/Storage/ProfileStore.swift ios/GhostCrabTests/Support/InMemoryTokenStore.swift ios/GhostCrabTests/Storage/ProfileStoreTests.swift
git commit -m "fix(ios): never overwrite an unreadable saved-profile list" -m "ProfileStore decoded with try? ?? [], so a corrupt blob showed zero profiles and the next save destroyed it. Writes now back the blob up to a timestamped UserDefaults key, clear it and throw ProfileStoreError.corruptProfileData; reads log and stay non-destructive. Adds a TokenStoring seam for tests."
```

---

### Task 14: Log a failed token cleanup instead of swallowing it with `try?`

**Files:**
- Modify: `ios/GhostCrab/Data/Impl/ConnectionProfileRepositoryImpl.swift:40-58` (`getToken`, doc included)
- Test: `ios/GhostCrabTests/Storage/ConnectionProfileRepositoryImplTests.swift`

Decision: the frozen `ConnectionProfileRepository.getToken` contract says it throws `GatewayError.profileNeedsReauth`, and callers depend on that to prompt a reconnect. So the cleanup failure at `:55` (`try? await store.delete(id:)`) is now logged through `PrivacySafeLogger` with its cause, and the contract error still propagates. The orphaned token heals on the next `saveProfile` (Keychain update-or-add) or `deleteProfile` (which always retries the token delete). Throwing the raw `KeychainError` would break the documented contract. These tests pin the contract; the log line itself cannot be observed in a unit test.

- [ ] **Step 1: Write the contract tests**

Create `ios/GhostCrabTests/Storage/ConnectionProfileRepositoryImplTests.swift`:

```swift
import Testing
import Foundation
@testable import GhostCrab

@Suite("ConnectionProfileRepositoryImpl token recovery")
struct ConnectionProfileRepositoryImplTests {

    private func makeRepo() throws -> (ConnectionProfileRepositoryImpl, ProfileStore, InMemoryTokenStore) {
        let defaults = try #require(UserDefaults(suiteName: "RepoTests.\(UUID().uuidString)"))
        let tokens = InMemoryTokenStore()
        let store = ProfileStore(defaults: defaults, keychain: tokens)
        return (ConnectionProfileRepositoryImpl(store: store), store, tokens)
    }

    private func makeProfile() -> ConnectionProfile {
        ConnectionProfile(id: UUID(), displayName: "Basement", url: "http://192.168.1.20:18789", lastConnectedAt: nil, hasToken: true)
    }

    private func expectReauth(_ repo: ConnectionProfileRepositoryImpl, profileId: String) async {
        do {
            _ = try await repo.getToken(profileId: profileId)
            Issue.record("expected GatewayError.profileNeedsReauth")
        } catch GatewayError.profileNeedsReauth(let id) {
            #expect(id == profileId)
        } catch {
            Issue.record("unexpected error: \(error)")
        }
    }

    @Test func unreadableTokenThrowsReauthAndClearsTheEntry() async throws {
        let (repo, store, tokens) = try makeRepo()
        let profile = makeProfile()
        try await repo.saveProfile(profile, token: "tok_123")
        tokens.simulateFailures(reads: true, deletes: false)

        await expectReauth(repo, profileId: profile.id.uuidString)

        #expect(await store.getAll().isEmpty)
        #expect(tokens.token(for: profile.id.uuidString) == nil)
    }

    @Test func failedCleanupStillThrowsReauth() async throws {
        let (repo, store, tokens) = try makeRepo()
        let profile = makeProfile()
        try await repo.saveProfile(profile, token: "tok_123")
        tokens.simulateFailures(reads: true, deletes: true)

        await expectReauth(repo, profileId: profile.id.uuidString)

        // Metadata goes first; the token is orphaned (and logged) until the next save/delete.
        #expect(await store.getAll().isEmpty)
        #expect(tokens.token(for: profile.id.uuidString) == "tok_123")
    }
}
```

- [ ] **Step 2: Run the tests (characterisation; they pass before and after)** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

```bash
cd ios && xcodegen generate && xcodebuild test -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:GhostCrabTests/ConnectionProfileRepositoryImplTests -quiet
```
Expected: `** TEST SUCCEEDED **`.

- [ ] **Step 3: Replace the `try?`**

In `ios/GhostCrab/Data/Impl/ConnectionProfileRepositoryImpl.swift`, add under `private let store: ProfileStore`:

```swift
    private static let log = PrivacySafeLogger(category: "profiles")
```

Replace `getToken(profileId:)` (lines 40-58, doc included):

```swift
    /// Retrieves the stored bearer token for a profile.
    ///
    /// - Parameter profileId: The ``ConnectionProfile/id`` (as `UUID.uuidString`)
    ///   to look up.
    /// - Returns: The bearer token, or `nil` if none was stored.
    /// - Throws: ``GatewayError/profileNeedsReauth(profileId:)`` if the Keychain
    ///   entry exists but cannot be read back (e.g. after a factory reset). The
    ///   profile and its token are cleared first. If that cleanup itself fails,
    ///   the failure is logged and the same contract error is still thrown; the
    ///   orphaned token is overwritten by the next save or removed by the next
    ///   delete of this profile.
    public func getToken(profileId: String) async throws -> String? {
        do {
            return try store.getToken(for: profileId)
        } catch {
            Self.log.error("Token for profile \(profileId) is unreadable; clearing the profile", error: error)
            do {
                try await store.delete(id: profileId)
            } catch let cleanupError {
                Self.log.error("Clearing profile \(profileId) failed; its token stays orphaned until the next save or delete", error: cleanupError)
            }
            throw GatewayError.profileNeedsReauth(profileId: profileId)
        }
    }
```

- [ ] **Step 4: Run the tests again** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

```bash
cd ios && xcodegen generate && xcodebuild test -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:GhostCrabTests/ConnectionProfileRepositoryImplTests -only-testing:GhostCrabTests/ProfileStoreTests -quiet
```
Expected: `** TEST SUCCEEDED **`.

- [ ] **Step 5: Commit**

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add ios/GhostCrab/Data/Impl/ConnectionProfileRepositoryImpl.swift ios/GhostCrabTests/Storage/ConnectionProfileRepositoryImplTests.swift
git commit -m "fix(ios): log failed token cleanup instead of swallowing it" -m "getToken's heal path used try? await store.delete, hiding an orphaned Keychain entry. The failure is now logged via PrivacySafeLogger and the frozen contract error (profileNeedsReauth) still propagates. Tests pin both paths."
```

---

### Task 15: API client test seam, a parallel-safe `MockURLProtocol`, and endpoint/error-mapping tests

**Files:**
- Modify: `ios/GhostCrab/Data/Api/OpenClawAPIClient.swift:30` (init signature), `:46` (protocol classes), after `:101` (factory)
- Rewrite: `ios/GhostCrabTests/Support/MockURLProtocol.swift`
- Test: `ios/GhostCrabTests/Api/OpenClawAPIClientTests.swift`

`MockURLProtocol` exists but nothing uses it, and its doc refers to a `forTest` factory that does not exist. Its single static `stub` would also race under parallel Swift Testing. The rewrite keys stubs by host and gives each test a unique host.

- [ ] **Step 1: Rewrite `MockURLProtocol`**

Replace the whole content of `ios/GhostCrabTests/Support/MockURLProtocol.swift`:

```swift
import Foundation

/// `URLProtocol` stub for `OpenClawAPIClient` tests (the analogue of Ktor's
/// `MockEngine` in `OpenClawApiClientTest.kt`).
///
/// Swift Testing runs suites in parallel, so stubs are keyed by host. Every
/// test makes its own base URL with ``uniqueBaseURL(scheme:port:)`` (or uses
/// a fixed IP literal no other test uses), registers a handler for it, and
/// reads back only its own requests. Usage:
///
///     let base = MockURLProtocol.uniqueBaseURL()
///     MockURLProtocol.register(base) { _ in .init(status: 200, json: #"{"status":"ok"}"#) }
///     let client = OpenClawAPIClient.makeForTesting(baseURL: base, protocolClasses: [MockURLProtocol.self])
final class MockURLProtocol: URLProtocol, @unchecked Sendable {

    struct Stub: Sendable {
        let status: Int
        let headers: [String: String]
        let body: Data

        init(status: Int, body: Data = Data(), headers: [String: String] = ["Content-Type": "application/json"]) {
            self.status = status
            self.body = body
            self.headers = headers
        }

        init(status: Int, json: String, headers: [String: String] = ["Content-Type": "application/json"]) {
            self.init(status: status, body: Data(json.utf8), headers: headers)
        }

        /// An HTML page, like upstream `ghcr.io/openclaw/openclaw` serves at `/status` and `/config`.
        static func html(status: Int = 200, body: String = "<!doctype html><title>OpenClaw</title>") -> Stub {
            Stub(status: status, body: Data(body.utf8), headers: ["Content-Type": "text/html; charset=utf-8"])
        }
    }

    typealias Handler = @Sendable (URLRequest) -> Stub

    private static let lock = NSLock()
    nonisolated(unsafe) private static var handlers: [String: Handler] = [:]
    nonisolated(unsafe) private static var captured: [String: [URLRequest]] = [:]

    /// A base URL whose host no other test uses.
    static func uniqueBaseURL(scheme: String = "http", port: Int = 18789) -> URL {
        let host = "gw-\(UUID().uuidString.lowercased()).mock"
        guard let url = URL(string: "\(scheme)://\(host):\(port)") else {
            preconditionFailure("generated mock URL is always valid")
        }
        return url
    }

    /// Routes every request to `baseURL`'s host through `handler` and resets that host's capture log.
    static func register(_ baseURL: URL, handler: @escaping Handler) {
        guard let host = baseURL.host else { return }
        lock.withLock {
            handlers[host] = handler
            captured[host] = []
        }
    }

    /// Requests that reached `baseURL`'s host, in order.
    static func requests(to baseURL: URL) -> [URLRequest] {
        guard let host = baseURL.host else { return [] }
        return lock.withLock { captured[host] ?? [] }
    }

    // MARK: - URLProtocol

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url, let host = url.host else {
            client?.urlProtocol(self, didFailWithError: URLError(.badURL))
            return
        }
        let current = request
        let handler: Handler? = Self.lock.withLock {
            Self.captured[host, default: []].append(current)
            return Self.handlers[host]
        }
        guard let handler else {
            client?.urlProtocol(self, didFailWithError: URLError(.cannotFindHost))
            return
        }
        let stub = handler(current)
        guard let response = HTTPURLResponse(url: url, statusCode: stub.status, httpVersion: "HTTP/1.1", headerFields: stub.headers) else {
            client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse))
            return
        }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: stub.body)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
```

- [ ] **Step 2: Write the endpoint and error-mapping tests**

Create `ios/GhostCrabTests/Api/OpenClawAPIClientTests.swift`:

```swift
import Testing
import Foundation
@testable import GhostCrab

@Suite("OpenClawAPIClient endpoints and error mapping")
struct OpenClawAPIClientTests {

    private static let aiRequest = AIRecommendationRequestDTO(
        query: "fastest local model?",
        context: AIContextDTO(activeConfig: nil, hardwareInfo: nil, activeModelId: nil)
    )

    private func makeClient(
        token: String? = nil,
        _ handler: @escaping MockURLProtocol.Handler
    ) -> (client: OpenClawAPIClient, base: URL) {
        let base = MockURLProtocol.uniqueBaseURL()
        MockURLProtocol.register(base, handler: handler)
        let client = OpenClawAPIClient.makeForTesting(baseURL: base, token: token, protocolClasses: [MockURLProtocol.self])
        return (client, base)
    }

    @Test func healthDecodesJSON() async throws {
        let (client, base) = makeClient { _ in .init(status: 200, json: #"{"status":"ok"}"#) }
        #expect(try await client.health().status == "ok")
        #expect(MockURLProtocol.requests(to: base).first?.url?.path == "/health")
    }

    @Test func statusToleratesUpstreamHTMLAdminPage() async throws {
        let (client, _) = makeClient { _ in .html() }
        #expect(try await client.status() == StatusResponse())
    }

    @Test func configHTMLMeansNoConfigAPI() async throws {
        let (client, _) = makeClient { _ in .html() }
        let config = try await client.getConfig()
        #expect(config.sections.isEmpty)
        #expect(config.etag == nil)
    }

    @Test(arguments: [401, 403])
    func authStatusesMapToAuthError(code: Int) async {
        let (client, base) = makeClient { _ in .init(status: code, json: "{}") }
        do {
            _ = try await client.status()
            Issue.record("expected GatewayError.auth")
        } catch GatewayError.auth(let url, let statusCode) {
            #expect(statusCode == code)
            #expect(url == base.absoluteString + "/status")
        } catch {
            Issue.record("unexpected error: \(error)")
        }
    }

    @Test func modelsNotFoundMapsToApi404() async {
        let (client, _) = makeClient { _ in .init(status: 404, json: "{}") }
        do {
            _ = try await client.getModels()
            Issue.record("expected GatewayError.api")
        } catch GatewayError.api(_, let statusCode, _) {
            #expect(statusCode == 404)
        } catch {
            Issue.record("unexpected error: \(error)")
        }
    }

    @Test func aiRecommend404MeansSkillNotInstalled() async {
        let (client, base) = makeClient { _ in .init(status: 404, json: "{}") }
        do {
            _ = try await client.getAIRecommendation(Self.aiRequest)
            Issue.record("expected GatewayError.aiServiceUnavailable")
        } catch GatewayError.aiServiceUnavailable(let url) {
            #expect(url == base.absoluteString + "/api/ai/recommend")
        } catch {
            Issue.record("unexpected error: \(error)")
        }
    }

    @Test func aiRecommend429MeansQuotaExceeded() async {
        let (client, _) = makeClient { _ in .init(status: 429, json: "{}") }
        do {
            _ = try await client.getAIRecommendation(Self.aiRequest)
            Issue.record("expected GatewayError.aiQuotaExceeded")
        } catch GatewayError.aiQuotaExceeded {
            // expected
        } catch {
            Issue.record("unexpected error: \(error)")
        }
    }

    @Test func configPatchSendsIfMatchAndMaps412() async throws {
        let (client, base) = makeClient { _ in .init(status: 412, json: "{}") }
        do {
            try await client.updateConfig(section: "gateway", value: AnyCodable(["port": 18790]), etag: "\"v1\"")
            Issue.record("expected GatewayError.api 412")
        } catch GatewayError.api(_, let statusCode, _) {
            #expect(statusCode == 412)
        } catch {
            Issue.record("unexpected error: \(error)")
        }
        let request = try #require(MockURLProtocol.requests(to: base).first)
        #expect(request.httpMethod == "PATCH")
        #expect(request.url?.path == "/config/gateway")
        #expect(request.value(forHTTPHeaderField: "If-Match") == "\"v1\"")
        #expect(request.value(forHTTPHeaderField: "Content-Type") == "application/merge-patch+json")
    }

    @Test func configPatch422WithFieldIsValidationError() async {
        let (client, _) = makeClient { _ in
            .init(status: 422, json: #"{"field":"gateway.http.port","reason":"must be 1-65535"}"#)
        }
        do {
            try await client.updateConfig(section: "gateway", value: AnyCodable(["port": 0]), etag: nil)
            Issue.record("expected GatewayError.configValidation")
        } catch GatewayError.configValidation(let field, let reason) {
            #expect(field == "gateway.http.port")
            #expect(reason == "must be 1-65535")
        } catch {
            Issue.record("unexpected error: \(error)")
        }
    }

    @Test func configPatch422WithoutFieldKeepsTheBody() async {
        let (client, _) = makeClient { _ in .init(status: 422, json: #"{"error":"schema"}"#) }
        do {
            try await client.updateConfig(section: "gateway", value: AnyCodable(["port": 0]), etag: nil)
            Issue.record("expected GatewayError.api 422")
        } catch GatewayError.api(_, let statusCode, let body) {
            #expect(statusCode == 422)
            #expect(body == #"{"error":"schema"}"#)
        } catch {
            Issue.record("unexpected error: \(error)")
        }
    }

    @Test func authenticatedClientSendsBearerToken() async throws {
        let (client, base) = makeClient(token: "tok_secret") { _ in .init(status: 200, json: #"{"status":"ok"}"#) }
        _ = try await client.health()
        let request = try #require(MockURLProtocol.requests(to: base).first)
        #expect(request.value(forHTTPHeaderField: "Authorization") == "Bearer tok_secret")
    }

    @Test func unauthenticatedClientSendsNoAuthorization() async throws {
        let (client, base) = makeClient { _ in .init(status: 200, json: #"{"status":"ok"}"#) }
        _ = try await client.health()
        let request = try #require(MockURLProtocol.requests(to: base).first)
        #expect(request.value(forHTTPHeaderField: "Authorization") == nil)
    }

    @Test func setActiveModelPostsToActiveEndpoint() async throws {
        let (client, base) = makeClient { _ in .init(status: 204) }
        try await client.setActiveModel("m2")
        let request = try #require(MockURLProtocol.requests(to: base).first)
        #expect(request.httpMethod == "POST")
        #expect(request.url?.path == "/api/models/active")
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

```bash
cd ios && xcodegen generate && xcodebuild test -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:GhostCrabTests/OpenClawAPIClientTests -quiet
```
Expected: the build fails with `type 'OpenClawAPIClient' has no member 'makeForTesting'`, then `** TEST FAILED **`.

- [ ] **Step 4: Add the seam**

In `ios/GhostCrab/Data/Api/OpenClawAPIClient.swift`, change the init signature (line 30) from `private init(baseURL: URL, token: String?, allowCleartextPublicIPs: Bool) {` to:

```swift
    private init(
        baseURL: URL,
        token: String?,
        allowCleartextPublicIPs: Bool,
        additionalProtocolClasses: [AnyClass] = []
    ) {
```

Change line 46 from `config.protocolClasses = [CleartextPublicIPGuard.self] + (config.protocolClasses ?? [])` to:

```swift
        config.protocolClasses = [CleartextPublicIPGuard.self] + additionalProtocolClasses + (config.protocolClasses ?? [])
```

Insert after the `authenticated(baseURL:token:allowCleartextPublicIPs:)` factory (after line 101):

```swift

    /// Test seam: the same client the production factories build, plus extra
    /// `URLProtocol` classes (e.g. `MockURLProtocol`) that are consulted after
    /// ``CleartextPublicIPGuard``, so guard behaviour stays under test.
    /// Internal, so only reachable through `@testable import`.
    ///
    /// - Parameters:
    ///   - baseURL: Gateway base URL.
    ///   - token: Bearer token, or `nil` for an unauthenticated client.
    ///   - allowCleartextPublicIPs: Same meaning as in the production factories.
    ///   - protocolClasses: Protocol classes appended after the guard.
    /// - Returns: A client whose session routes through `protocolClasses`.
    static func makeForTesting(
        baseURL: URL,
        token: String? = nil,
        allowCleartextPublicIPs: Bool = false,
        protocolClasses: [AnyClass]
    ) -> OpenClawAPIClient {
        OpenClawAPIClient(
            baseURL: baseURL,
            token: token,
            allowCleartextPublicIPs: allowCleartextPublicIPs,
            additionalProtocolClasses: protocolClasses
        )
    }
```

- [ ] **Step 5: Run the tests to verify they pass** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

```bash
cd ios && xcodegen generate && xcodebuild test -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:GhostCrabTests/OpenClawAPIClientTests -quiet
```
Expected: `** TEST SUCCEEDED **` (these tests describe existing behaviour, now under test).

- [ ] **Step 6: Commit**

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add ios/GhostCrab/Data/Api/OpenClawAPIClient.swift ios/GhostCrabTests/Support/MockURLProtocol.swift ios/GhostCrabTests/Api/OpenClawAPIClientTests.swift
git commit -m "test(ios): cover OpenClawAPIClient endpoints and error mapping" -m "Adds an internal makeForTesting seam (mock protocols run after CleartextPublicIPGuard) and rewrites MockURLProtocol with a per-host registry safe under parallel Swift Testing. Covers 401/403, 404, 429, 412 + If-Match, 422 validation, bearer header and the upstream HTML /status quirk."
```

---

### Task 16: A broken `/status` or `/models` body is an error, not "empty"

**Files:**
- Modify: `ios/GhostCrab/Data/Api/OpenClawAPIClient.swift:134-142` (`status`), `:214-222` (`getModels`)
- Test: `ios/GhostCrabTests/Api/OpenClawAPIClientDecodingTests.swift`

Rule: a non-JSON `Content-Type` keeps the documented lenient behaviour (upstream serves HTML at `/status`). A body labelled JSON that does not decode now throws. The existing `safe(url:)` mapping turns that into `GatewayError.api(statusCode: 0, body: "Response deserialization failed: …")`, the same way `getAIRecommendation` and `installSkill` already fail. Android does the same: `OpenClawApiClient.kt:189-190` calls `response.body()` and throws on bad JSON.

- [ ] **Step 1: Write the failing tests**

Create `ios/GhostCrabTests/Api/OpenClawAPIClientDecodingTests.swift`:

```swift
import Testing
import Foundation
@testable import GhostCrab

@Suite("OpenClawAPIClient lenient vs strict decoding")
struct OpenClawAPIClientDecodingTests {

    private func makeClient(_ handler: @escaping MockURLProtocol.Handler) -> OpenClawAPIClient {
        let base = MockURLProtocol.uniqueBaseURL()
        MockURLProtocol.register(base, handler: handler)
        return OpenClawAPIClient.makeForTesting(baseURL: base, protocolClasses: [MockURLProtocol.self])
    }

    private func expectDeserializationFailure(_ body: () async throws -> Void) async {
        do {
            try await body()
            Issue.record("expected GatewayError.api for JSON that does not match the schema")
        } catch GatewayError.api(_, let statusCode, let responseBody) {
            #expect(statusCode == 0)
            #expect(responseBody?.hasPrefix("Response deserialization failed") == true)
        } catch {
            Issue.record("unexpected error: \(error)")
        }
    }

    @Test func statusHTMLStillYieldsDefaults() async throws {
        let client = makeClient { _ in .html() }
        #expect(try await client.status() == StatusResponse())
    }

    @Test func statusJSONThatIsNotAnObjectIsAnError() async {
        let client = makeClient { _ in .init(status: 200, json: "[1,2,3]") }
        await expectDeserializationFailure { _ = try await client.status() }
    }

    @Test func modelsHTMLStillYieldsEmptyList() async throws {
        let client = makeClient { _ in .html() }
        #expect(try await client.getModels().isEmpty)
    }

    @Test func modelsEmptyJSONArrayIsEmptyList() async throws {
        let client = makeClient { _ in .init(status: 200, json: "[]") }
        #expect(try await client.getModels().isEmpty)
    }

    @Test func modelsJSONWithWrongShapeIsAnError() async {
        let client = makeClient { _ in .init(status: 200, json: #"{"error":"provider registry offline"}"#) }
        await expectDeserializationFailure { _ = try await client.getModels() }
    }

    @Test func modelsJSONDecodes() async throws {
        let client = makeClient { _ in
            .init(status: 200, json: #"[{"id":"m1","provider":"openai","isActive":true}]"#)
        }
        let models = try await client.getModels()
        #expect(models.map(\.id) == ["m1"])
        #expect(models.first?.isActive == true)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

```bash
cd ios && xcodegen generate && xcodebuild test -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:GhostCrabTests/OpenClawAPIClientDecodingTests -quiet
```
Expected: `** TEST FAILED **`. `statusJSONThatIsNotAnObjectIsAnError` and `modelsJSONWithWrongShapeIsAnError` both record `expected GatewayError.api for JSON that does not match the schema`.

- [ ] **Step 3: Decode strictly once the content type says JSON**

In `status()`, replace:
```swift
            guard self.contentTypeIsJSON(response) else { return StatusResponse() }
            return (try? self.decoder.decode(StatusResponse.self, from: data)) ?? StatusResponse()
```
with:
```swift
            // Non-JSON (upstream HTML admin page): lenient defaults, documented above.
            guard self.contentTypeIsJSON(response) else { return StatusResponse() }
            // Labelled JSON but undecodable: a broken gateway, not an empty one.
            return try self.decoder.decode(StatusResponse.self, from: data)
```
and add to the `status()` doc comment:
```swift
    /// - Throws: `GatewayError.api` (statusCode 0, body "Response deserialization failed…")
    ///   when the body is labelled JSON but is not a JSON object.
```

In `getModels()`, replace:
```swift
            guard self.contentTypeIsJSON(response) else { return [] }
            return (try? self.decoder.decode([ModelDTO].self, from: data)) ?? []
```
with:
```swift
            guard self.contentTypeIsJSON(response) else { return [] }
            return try self.decoder.decode([ModelDTO].self, from: data)
```
and add to its doc comment:
```swift
    /// - Throws: `GatewayError.api` (statusCode 0, body "Response deserialization failed…")
    ///   when the body is labelled JSON but is not an array of models. An empty
    ///   list therefore always means the gateway reported no models.
```

- [ ] **Step 4: Run the tests to verify they pass** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

```bash
cd ios && xcodegen generate && xcodebuild test -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:GhostCrabTests/OpenClawAPIClientDecodingTests -only-testing:GhostCrabTests/OpenClawAPIClientTests -quiet
```
Expected: `** TEST SUCCEEDED **`.

- [ ] **Step 5: Commit**

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add ios/GhostCrab/Data/Api/OpenClawAPIClient.swift ios/GhostCrabTests/Api/OpenClawAPIClientDecodingTests.swift
git commit -m "fix(ios): surface undecodable /status and /models JSON instead of empty defaults" -m "A JSON-labelled body that fails to decode now throws GatewayError.api (Response deserialization failed), so a broken gateway no longer looks like one with no models. Non-JSON bodies keep the lenient path for the upstream HTML /status quirk. Matches Android."
```

---

### Task 17: Real timeouts, honestly named

**Files:**
- Modify: `ios/GhostCrab/Data/Api/OpenClawAPIClient.swift:12` (doc), `:41-56` (session config), `send(_:)`, the `getAIRecommendation` and `installSkill` call sites
- Test: `ios/GhostCrabTests/Api/OpenClawAPIClientTimeoutTests.swift`

URLSession has no connect timeout. `URLRequest.timeoutInterval` is an idle timeout (the longest gap between bytes, including the wait for the first byte), and `timeoutIntervalForResource` caps a whole transfer. The `if #available` block at `:48-54` contains only comments, so the "15 s connect timeout" never existed, and the 30 s resource cap cuts off skill installs and AI calls. After this task, every request sets its own `timeoutInterval`: 15 s for short calls, 120 s for skill install and AI recommend. The session's 120 s value only acts as a ceiling.

- [ ] **Step 1: Write the failing tests**

Create `ios/GhostCrabTests/Api/OpenClawAPIClientTimeoutTests.swift`:

```swift
import Testing
import Foundation
@testable import GhostCrab

@Suite("OpenClawAPIClient timeouts")
struct OpenClawAPIClientTimeoutTests {

    private func makeClient(_ handler: @escaping MockURLProtocol.Handler) -> (OpenClawAPIClient, URL) {
        let base = MockURLProtocol.uniqueBaseURL()
        MockURLProtocol.register(base, handler: handler)
        return (OpenClawAPIClient.makeForTesting(baseURL: base, protocolClasses: [MockURLProtocol.self]), base)
    }

    @Test func timeoutConstantsHaveTheDocumentedValues() {
        #expect(OpenClawAPIClient.Timeouts.standard == 15)
        #expect(OpenClawAPIClient.Timeouts.longOperation == 120)
        #expect(OpenClawAPIClient.Timeouts.resource == 180)
    }

    @Test func healthUsesStandardTimeout() async throws {
        let (client, base) = makeClient { _ in .init(status: 200, json: #"{"status":"ok"}"#) }
        _ = try await client.health()
        let request = try #require(MockURLProtocol.requests(to: base).first)
        #expect(request.timeoutInterval == OpenClawAPIClient.Timeouts.standard)
    }

    @Test func skillInstallUsesLongTimeout() async throws {
        let (client, base) = makeClient { _ in
            .init(status: 200, json: #"{"slug":"wanng-ide/auto-skill-hunter","installed_version":"1.2.0"}"#)
        }
        _ = try await client.installSkill(slug: "wanng-ide/auto-skill-hunter", version: nil)
        let request = try #require(MockURLProtocol.requests(to: base).first)
        #expect(request.timeoutInterval == OpenClawAPIClient.Timeouts.longOperation)
    }

    @Test func aiRecommendUsesLongTimeout() async throws {
        let (client, base) = makeClient { _ in .init(status: 200, json: #"{"recommendation":"use m2"}"#) }
        _ = try await client.getAIRecommendation(AIRecommendationRequestDTO(
            query: "q",
            context: AIContextDTO(activeConfig: nil, hardwareInfo: nil, activeModelId: nil)
        ))
        let request = try #require(MockURLProtocol.requests(to: base).first)
        #expect(request.timeoutInterval == OpenClawAPIClient.Timeouts.longOperation)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

```bash
cd ios && xcodegen generate && xcodebuild test -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:GhostCrabTests/OpenClawAPIClientTimeoutTests -quiet
```
Expected: the build fails with `type 'OpenClawAPIClient' has no member 'Timeouts'`, then `** TEST FAILED **`.

- [ ] **Step 3: Implement**

In `ios/GhostCrab/Data/Api/OpenClawAPIClient.swift`, replace the header doc line `/// Timeouts: connect = 15s, request = 30s.` with:

```swift
/// Timeouts: see ``Timeouts``. Each request sets its own idle timeout: 15 s for
/// short calls, 120 s for skill install / AI recommend. 180 s caps any transfer.
```

Insert at the top of the actor body, before `// MARK: - State`:

```swift
    /// Request timeout policy.
    ///
    /// URLSession has no separate TCP-connect timeout. `URLRequest.timeoutInterval`
    /// is an idle timeout (the longest gap between bytes, including the wait for
    /// the first byte), and `timeoutIntervalForResource` caps a whole transfer.
    /// So a dead or black-holed host fails after ``standard`` seconds on every
    /// short call, while slow gateway-side work gets ``longOperation``.
    enum Timeouts {
        /// Probe, `/health`, `/status`, config, models, whoami, uninstall.
        static let standard: TimeInterval = 15
        /// Skill install and AI recommendation: the gateway works before it answers.
        static let longOperation: TimeInterval = 120
        /// Upper bound for any single transfer.
        static let resource: TimeInterval = 180
    }

```

Replace the session configuration (from `let config = URLSessionConfiguration.ephemeral` through `self.session = URLSession(configuration: config)`, which includes the dead `if #available` block):

```swift
        let config = URLSessionConfiguration.ephemeral
        // Ceiling only: `send(_:timeout:)` sets every request's own timeoutInterval.
        config.timeoutIntervalForRequest = Timeouts.longOperation
        config.timeoutIntervalForResource = Timeouts.resource
        config.waitsForConnectivity = false
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.protocolClasses = [CleartextPublicIPGuard.self] + additionalProtocolClasses + (config.protocolClasses ?? [])

        self.session = URLSession(configuration: config)
```

Replace `send(_:)`:

```swift
    /// Sends a request with the Authorization header injected when a token is present.
    /// Logs at debug level with the Authorization header stripped.
    ///
    /// - Parameters:
    ///   - request: The request to send.
    ///   - timeout: Idle timeout for this request. Defaults to ``Timeouts/standard``.
    private func send(
        _ request: URLRequest,
        timeout: TimeInterval = Timeouts.standard
    ) async throws -> (Data, URLResponse) {
        var req = request
        req.timeoutInterval = timeout
        if let token {
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        Self.logDebug(req)
        return try await session.data(for: req)
    }
```

In `getAIRecommendation(_:)` and in `installSkill(slug:version:)`, change `let (data, response) = try await self.send(req)` to:

```swift
            let (data, response) = try await self.send(req, timeout: Timeouts.longOperation)
```

- [ ] **Step 4: Run the tests to verify they pass** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

```bash
cd ios && xcodegen generate && xcodebuild test -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:GhostCrabTests/OpenClawAPIClientTimeoutTests -only-testing:GhostCrabTests/OpenClawAPIClientTests -quiet
```
Expected: `** TEST SUCCEEDED **`.

- [ ] **Step 5: Check the timeout on a real network** ⚠ manual (simulator)

In Manual Entry, use host `10.255.255.1` (unroutable), port `18789`, HTTP, and tap Connect. Expected: the error `Gateway request timed out at http://10.255.255.1:18789/health` appears after about 15 s, not 30 or 60. This confirms URLSession honours the per-request value over the session value.

- [ ] **Step 6: Commit**

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add ios/GhostCrab/Data/Api/OpenClawAPIClient.swift ios/GhostCrabTests/Api/OpenClawAPIClientTimeoutTests.swift
git commit -m "fix(ios): implement per-request timeouts in OpenClawAPIClient" -m "The claimed 15s connect timeout was a comment-only if #available block, and a 30s resource cap cut off skill installs and AI calls. Every request now sets its own idle timeout (15s standard, 120s for install/recommend) under a 180s resource cap, via named Timeouts constants."
```

---

### Task 18: Make the cleartext public-IP guard per client

**Files:**
- Modify: `ios/GhostCrab/Data/Api/CleartextPublicIPGuard.swift:3-63` (doc, configuration, `canInit`)
- Modify: `ios/GhostCrab/Data/Api/OpenClawAPIClient.swift` (stored flag, init, `send`)
- Test: `ios/GhostCrabTests/Api/CleartextGuardIsolationTests.swift`

Today every client's init calls `CleartextPublicIPGuard.configure { allowCleartextPublicIPs }` (`OpenClawAPIClient.swift:39`), so the last client constructed sets the policy for all of them; the comment says as much. After this task, each request carries the opt-in as a `URLProtocol` property, set in `send`, and the guard reads it in `canInit`. A request without the property is blocked (fail-safe).

- [ ] **Step 1: Write the failing tests**

Create `ios/GhostCrabTests/Api/CleartextGuardIsolationTests.swift`:

```swift
import Testing
import Foundation
@testable import GhostCrab

/// Each test uses its own IP literal so parallel tests never share a mock host.
@Suite("Cleartext guard is per client")
struct CleartextGuardIsolationTests {

    @Test func clientsWithDifferentFlagsDoNotInterfere() async throws {
        let base = try #require(URL(string: "http://8.8.4.4:18789"))
        MockURLProtocol.register(base) { _ in .init(status: 200, json: #"{"status":"ok"}"#) }

        let blocked = OpenClawAPIClient.makeForTesting(baseURL: base, allowCleartextPublicIPs: false, protocolClasses: [MockURLProtocol.self])
        // Built last: under the old process-global closure this flipped `blocked` to allowed too.
        let allowed = OpenClawAPIClient.makeForTesting(baseURL: base, allowCleartextPublicIPs: true, protocolClasses: [MockURLProtocol.self])

        #expect(try await allowed.health().status == "ok")
        await #expect(throws: GatewayError.self) {
            _ = try await blocked.health()
        }
    }

    @Test func blockedRequestNeverReachesTheNetwork() async throws {
        let base = try #require(URL(string: "http://9.9.9.9:18789"))
        MockURLProtocol.register(base) { _ in .init(status: 200, json: #"{"status":"ok"}"#) }
        let client = OpenClawAPIClient.makeForTesting(baseURL: base, allowCleartextPublicIPs: false, protocolClasses: [MockURLProtocol.self])

        await #expect(throws: GatewayError.self) {
            _ = try await client.health()
        }
        #expect(MockURLProtocol.requests(to: base).isEmpty)
    }

    @Test func privateIPIsAllowedWithTheFlagOff() async throws {
        let base = try #require(URL(string: "http://192.168.77.20:18789"))
        MockURLProtocol.register(base) { _ in .init(status: 200, json: #"{"status":"ok"}"#) }
        let client = OpenClawAPIClient.makeForTesting(baseURL: base, allowCleartextPublicIPs: false, protocolClasses: [MockURLProtocol.self])

        #expect(try await client.health().status == "ok")
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

```bash
cd ios && xcodegen generate && xcodebuild test -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:GhostCrabTests/CleartextGuardIsolationTests -quiet
```
Expected: `** TEST FAILED **`. `clientsWithDifferentFlagsDoNotInterfere` reports that no error was thrown, because `blocked` inherited `allowed`'s flag.

- [ ] **Step 3: Rewrite the guard's configuration**

In `ios/GhostCrab/Data/Api/CleartextPublicIPGuard.swift`, replace lines 3-63 (class doc, `Configuration`, `configure`, `canInit`) with:

```swift
/// `URLProtocol` subclass that blocks cleartext HTTP requests to public (non-LAN)
/// IP addresses unless the sending client opted in.
///
/// The opt-in travels on each request as a `URLProtocol` property
/// (``allowPropertyKey``), set by `OpenClawAPIClient.send` via
/// ``tagged(_:allowCleartextPublicIPs:)``. There is no process-global
/// switch, so two clients with different settings never interfere. A request
/// without the property is treated as "not allowed" (fail-safe).
///
/// Only numeric IPv4/IPv6 literals are evaluated. Hostname-based URLs pass
/// through; cleartext to qualified hostnames is blocked earlier by iOS ATS
/// (`docs/adr/0006-ios-ats-local-networking.md`).
///
/// Private ranges allowed by default: RFC-1918 (10/8, 172.16/12, 192.168/16),
/// link-local (169.254/16), and loopback (127/8). IPv6 loopback (`::1`),
/// link-local (`fe80::/10`) and unique-local (`fc00::/7`) all pass through.
///
/// Direct port of `CleartextPublicIpInterceptor.kt`.
public final class CleartextPublicIPGuard: URLProtocol, @unchecked Sendable {

    // MARK: - Per-request opt-in

    /// `URLProtocol` property key carrying the sending client's opt-in (`Bool`).
    static let allowPropertyKey = "com.qavren.ghostcrab.allowCleartextPublicIPs"

    /// Returns a copy of `request` tagged with the client's cleartext opt-in.
    ///
    /// - Parameters:
    ///   - request: Outgoing request.
    ///   - allowCleartextPublicIPs: The client's setting.
    /// - Returns: The tagged request. If the copy cannot be made mutable, the
    ///   untagged request is returned, which the guard treats as "not allowed".
    static func tagged(_ request: URLRequest, allowCleartextPublicIPs: Bool) -> URLRequest {
        guard let mutable = (request as NSURLRequest).mutableCopy() as? NSMutableURLRequest else {
            return request
        }
        URLProtocol.setProperty(allowCleartextPublicIPs, forKey: allowPropertyKey, in: mutable)
        return mutable as URLRequest
    }

    // MARK: - URLProtocol

    public override class func canInit(with request: URLRequest) -> Bool {
        guard let url = request.url else { return false }
        guard url.scheme?.lowercased() == "http" else { return false }
        guard let host = url.host else { return false }
        let allowed = (URLProtocol.property(forKey: allowPropertyKey, in: request) as? Bool) ?? false
        // Only intercept (and reject) if this is a public IP literal AND not allowed.
        return isPublicIPLiteral(host) && !allowed
    }
```

`canonicalRequest`, `startLoading`, `stopLoading` and the IP helpers below stay unchanged.

- [ ] **Step 4: Carry the flag per client**

In `ios/GhostCrab/Data/Api/OpenClawAPIClient.swift`, add to `// MARK: - State` under `private let token: String?`:

```swift
    /// This client's cleartext-to-public-IP opt-in; attached to every request in ``send(_:timeout:)``.
    private let allowCleartextPublicIPs: Bool
```

In the init, replace the comment block and the call:

```swift
        // Make sure the guard knows whether to permit cleartext public-IP calls.
        // The closure is captured by value here so per-client setting wins for
        // the lifetime of the configuration. If multiple clients exist with
        // differing flags, the most recently constructed one wins — same
        // semantics as Kotlin's per-engine interceptor closure.
        CleartextPublicIPGuard.configure { allowCleartextPublicIPs }
```
with:
```swift
        // Per-client opt-in: attached to each request in send(_:timeout:);
        // no process-global guard state.
        self.allowCleartextPublicIPs = allowCleartextPublicIPs
```

Replace `send(_:timeout:)`:

```swift
    /// Sends a request with the Authorization header injected when a token is present
    /// and this client's cleartext opt-in attached for ``CleartextPublicIPGuard``.
    /// Logs at debug level with the Authorization header stripped.
    ///
    /// - Parameters:
    ///   - request: The request to send.
    ///   - timeout: Idle timeout for this request. Defaults to ``Timeouts/standard``.
    private func send(
        _ request: URLRequest,
        timeout: TimeInterval = Timeouts.standard
    ) async throws -> (Data, URLResponse) {
        var req = request
        req.timeoutInterval = timeout
        if let token {
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        req = CleartextPublicIPGuard.tagged(req, allowCleartextPublicIPs: allowCleartextPublicIPs)
        Self.logDebug(req)
        return try await session.data(for: req)
    }
```

Check that no global configuration remains:

```bash
grep -rn "CleartextPublicIPGuard.configure\|configuration.isAllowed" ios/GhostCrab; echo "exit=$?"
```
Expected: no output, `exit=1`.

- [ ] **Step 5: Run the tests to verify they pass** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

```bash
cd ios && xcodegen generate && xcodebuild test -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:GhostCrabTests -quiet
```
Expected: `** TEST SUCCEEDED **`. The whole unit suite runs, because the guard sits in front of every client test.

- [ ] **Step 6: Commit**

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add ios/GhostCrab/Data/Api/CleartextPublicIPGuard.swift ios/GhostCrab/Data/Api/OpenClawAPIClient.swift ios/GhostCrabTests/Api/CleartextGuardIsolationTests.swift
git commit -m "fix(ios): make the cleartext public-IP guard per client" -m "CleartextPublicIPGuard.configure was process-global, so the last client constructed set the policy for every client (probe vs live session). The opt-in now rides on each request as a URLProtocol property set in send(); requests without it are blocked."
```

---

### Task 19: In-flight guards for ConnectionPicker and ManualEntry

**Files:**
- Modify: `ios/GhostCrab/UI/Connection/ConnectionPickerViewModel.swift:37-38` (state), `:83-116` (`connect`, `delete`)
- Modify: `ios/GhostCrab/UI/Connection/ManualEntryViewModel.swift` (`connectTask`, `connect()`)
- Test: `ios/GhostCrabTests/UI/ConnectionPickerViewModelTests.swift`, `ios/GhostCrabTests/UI/ManualEntryViewModelTests.swift`

Pattern for Tasks 19-22: store the `Task` in an `@ObservationIgnored private(set) var` (read-only outside the VM, so tests can `await vm.xTask?.value`), return early while one is running, and clear it when it finishes. A `deinit` cancel is pointless here, because each `Task` holds `self` strongly until it ends.

- [ ] **Step 1: Write the failing tests**

Create `ios/GhostCrabTests/UI/ConnectionPickerViewModelTests.swift`:

```swift
import Testing
import Foundation
@testable import GhostCrab

@MainActor
@Suite("ConnectionPickerViewModel")
struct ConnectionPickerViewModelTests {

    @Test func doubleTapConnectsOnceWithTheSavedToken() async {
        let profile = FakeConnectionProfileRepository.profile(hasToken: true)
        let profiles = FakeConnectionProfileRepository(profiles: [profile], tokens: [profile.id.uuidString: "tok_saved"])
        let gateway = FakeGatewayConnectionManager()
        let vm = ConnectionPickerViewModel(profileRepository: profiles, connectionManager: gateway)

        vm.connect(profile)
        vm.connect(profile)
        await vm.connectTask?.value

        #expect(gateway.connectURLs == [profile.url])
        #expect(gateway.connectTokens == ["tok_saved"])
        #expect(vm.navigateToDashboard)
        #expect(vm.connectingId == nil)
    }

    @Test func failedConnectSurfacesErrorAndClearsSpinner() async {
        let profile = FakeConnectionProfileRepository.profile()
        let gateway = FakeGatewayConnectionManager(connectError: .unreachable(url: profile.url))
        let vm = ConnectionPickerViewModel(
            profileRepository: FakeConnectionProfileRepository(profiles: [profile]),
            connectionManager: gateway
        )

        vm.connect(profile)
        await vm.connectTask?.value

        #expect(vm.lastError == "Gateway unreachable at \(profile.url)")
        #expect(!vm.navigateToDashboard)
        #expect(vm.connectingId == nil)
    }

    @Test func doubleSwipeDeletesOnceAndSurfacesFailure() async {
        let profile = FakeConnectionProfileRepository.profile()
        let profiles = FakeConnectionProfileRepository(
            profiles: [profile],
            deleteError: .api(url: "keychain://token", statusCode: 0)
        )
        let vm = ConnectionPickerViewModel(profileRepository: profiles, connectionManager: FakeGatewayConnectionManager())

        vm.delete(profile.id)
        vm.delete(profile.id)
        await vm.deleteTask?.value

        #expect(profiles.deleteAttempts == [profile.id.uuidString])
        #expect(vm.lastError?.hasPrefix("Failed to delete") == true)
    }
}
```

Add inside `struct ManualEntryViewModelTests`, after the last test:

```swift
    @Test func doubleTapConnectsOnceAndSavesProfileWithToken() async {
        let gateway = FakeGatewayConnectionManager()
        let profiles = FakeConnectionProfileRepository()
        let onboarding = FakeOnboardingRepository()
        let vm = makeVM(handoff: PairingHandoff(), gateway: gateway, profiles: profiles, onboarding: onboarding)
        vm.setHost("192.168.1.20")
        vm.token = "tok_123"

        vm.connect()
        vm.connect()
        await vm.connectTask?.value

        #expect(gateway.connectURLs == ["http://192.168.1.20:18789"])
        #expect(profiles.savedTokens == ["tok_123"])
        #expect(profiles.savedProfiles.first?.displayName == "Fake Gateway")
        #expect(onboarding.markCompletedCount == 1)
        #expect(vm.navigateToDashboard)
        #expect(!vm.isConnecting)
    }

    @Test func invalidHostNeverReachesTheGateway() {
        let gateway = FakeGatewayConnectionManager()
        let vm = makeVM(handoff: PairingHandoff(), gateway: gateway)
        vm.setHost("http://192.168.1.20")

        vm.connect()

        #expect(vm.hostError == "Remove scheme — use the HTTPS toggle instead")
        #expect(vm.connectTask == nil)
        #expect(gateway.connectURLs.isEmpty)
    }
```

- [ ] **Step 2: Run the tests to verify they fail** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

```bash
cd ios && xcodegen generate && xcodebuild test -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:GhostCrabTests/ConnectionPickerViewModelTests -only-testing:GhostCrabTests/ManualEntryViewModelTests -quiet
```
Expected: the build fails with `value of type 'ConnectionPickerViewModel' has no member 'connectTask'`, then `** TEST FAILED **`.

- [ ] **Step 3: Guard `ConnectionPickerViewModel`**

Add below `private var stateTask: Task<Void, Never>?`:

```swift

    /// In-flight connect, if any. Read-only outside the VM so tests can await it.
    @ObservationIgnored private(set) var connectTask: Task<Void, Never>?

    /// Most recent delete. Read-only outside the VM so tests can await it.
    @ObservationIgnored private(set) var deleteTask: Task<Void, Never>?

    /// Profile ids with a delete in flight; a second swipe on the same row is ignored.
    @ObservationIgnored private var pendingDeletes: Set<UUID> = []
```

Replace `connect(_:)` and `delete(_:)`:

```swift
    /// Attempts to connect to the given saved profile. On success, flips
    /// `navigateToDashboard` so the view can push `.dashboard`. Ignored while a
    /// connect is already in flight, so a double tap connects once.
    public func connect(_ profile: ConnectionProfile) {
        guard connectTask == nil else { return }
        self.connectingId = profile.id
        self.lastError = nil
        connectTask = Task {
            defer { self.connectTask = nil }
            do {
                let token = profile.hasToken
                    ? try await self.profileRepository.getToken(profileId: profile.id.uuidString)
                    : nil
                try await self.connectionManager.connect(url: profile.url, token: token)
                self.connectingId = nil
                self.navigateToDashboard = true
            } catch {
                self.connectingId = nil
                self.lastError = (error as? LocalizedError)?.errorDescription
                    ?? "Connection failed: \(String(describing: error))"
            }
        }
    }
```
```swift
    /// Deletes a saved profile and its associated keychain token. A repeat
    /// delete of the same profile while the first is in flight is ignored.
    public func delete(_ profileId: UUID) {
        guard pendingDeletes.insert(profileId).inserted else { return }
        deleteTask = Task {
            defer { self.pendingDeletes.remove(profileId) }
            do {
                try await self.profileRepository.deleteProfile(profileId: profileId.uuidString)
            } catch {
                self.lastError = "Failed to delete: \(String(describing: error))"
            }
        }
    }
```

- [ ] **Step 4: Guard `ManualEntryViewModel`**

Add below `public private(set) var navigateToDashboard: Bool = false`:

```swift

    /// In-flight connect, if any. Read-only outside the VM so tests can await it.
    @ObservationIgnored private(set) var connectTask: Task<Void, Never>?
```

Replace `connect()`:

```swift
    /// Validates the form, connects, saves the profile + token, marks onboarding
    /// completed, then flips ``navigateToDashboard``. Ignored while a connect is
    /// already in flight, so a double tap connects once.
    public func connect() {
        guard connectTask == nil else { return }
        let hostError = validateHost(host)
        let portError = validatePort(port)
        if hostError != nil || portError != nil {
            self.hostError = hostError
            self.portError = portError
            return
        }

        let url = self.assembledURL
        let tokenValue: String? = {
            let trimmed = token.trimmingCharacters(in: .whitespaces)
            return trimmed.isEmpty ? nil : trimmed
        }()

        self.isConnecting = true
        self.lastError = nil

        connectTask = Task {
            defer { self.connectTask = nil }
            do {
                try await self.connectionManager.connect(url: url, token: tokenValue)

                // Read display name from current state if connected.
                let displayName: String = {
                    if case let .connected(_, name, _, _, _, _, _, _) = self.connectionManager.currentState {
                        return name
                    }
                    return url
                }()

                // Look up any existing profile with this URL to preserve its id.
                let existingId: UUID? = await self.firstProfileId(matching: url)
                let profile = ConnectionProfile(
                    id: existingId ?? UUID(),
                    displayName: displayName,
                    url: url,
                    lastConnectedAt: Date(),
                    hasToken: tokenValue != nil
                )
                try await self.profileRepository.saveProfile(profile, token: tokenValue)
                await self.onboardingRepository.markCompleted()

                self.isConnecting = false
                self.navigateToDashboard = true
            } catch {
                self.isConnecting = false
                self.lastError = (error as? LocalizedError)?.errorDescription
                    ?? "Connect failed: \(String(describing: error))"
            }
        }
    }
```

- [ ] **Step 5: Run the tests to verify they pass** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

```bash
cd ios && xcodegen generate && xcodebuild test -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:GhostCrabTests/ConnectionPickerViewModelTests -only-testing:GhostCrabTests/ManualEntryViewModelTests -quiet
```
Expected: `** TEST SUCCEEDED **`.

- [ ] **Step 6: Commit**

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add ios/GhostCrab/UI/Connection/ConnectionPickerViewModel.swift ios/GhostCrab/UI/Connection/ManualEntryViewModel.swift ios/GhostCrabTests/UI/ConnectionPickerViewModelTests.swift ios/GhostCrabTests/UI/ManualEntryViewModelTests.swift
git commit -m "fix(ios): stop double taps from firing parallel connects and deletes" -m "ConnectionPicker and ManualEntry spawned untracked Tasks per tap. Connect/delete now run in tracked tasks with early-return guards (per-profile for deletes). Adds VM tests for both screens."
```

---

### Task 20: Onboarding moves one step per tap, and hydration never overrides a tap

**Files:**
- Modify (rewrite): `ios/GhostCrab/UI/Onboarding/OnboardingViewModel.swift`
- Test: `ios/GhostCrabTests/UI/OnboardingViewModelTests.swift`

Two problems today. `next()`, `back()` and `skip()` each spawn an untracked `Task` that reads `self.step` later, so a double tap can advance twice or call `markCompleted()` twice. And the hydration `Task` started in `init` can finish after the user's first tap and jump them back to the saved step.

- [ ] **Step 1: Write the failing tests**

Create `ios/GhostCrabTests/UI/OnboardingViewModelTests.swift`:

```swift
import Testing
@testable import GhostCrab

@MainActor
@Suite("OnboardingViewModel")
struct OnboardingViewModelTests {

    @Test func rapidNextAdvancesExactlyOneStep() async {
        let repo = FakeOnboardingRepository()
        let vm = OnboardingViewModel(repository: repo)
        await vm.loadTask?.value

        vm.next()
        vm.next()
        await vm.transitionTask?.value

        #expect(vm.step == .whatIsOpenClaw)
        #expect(repo.savedSteps == [.whatIsOpenClaw])
    }

    @Test func rapidSkipCompletesOnce() async {
        let repo = FakeOnboardingRepository()
        let vm = OnboardingViewModel(repository: repo)

        vm.skip()
        vm.skip()
        await vm.transitionTask?.value

        #expect(vm.isFinished)
        #expect(repo.markCompletedCount == 1)
    }

    @Test func hydrationNeverOverridesAnEarlyTap() async {
        let repo = FakeOnboardingRepository(savedStep: .startGateway)
        let vm = OnboardingViewModel(repository: repo)

        vm.next()  // before hydration has run
        await vm.loadTask?.value
        await vm.transitionTask?.value

        #expect(vm.step == .whatIsOpenClaw)
    }

    @Test func hydrationRestoresTheSavedStep() async {
        let vm = OnboardingViewModel(repository: FakeOnboardingRepository(savedStep: .startGateway))
        await vm.loadTask?.value
        #expect(vm.step == .startGateway)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

```bash
cd ios && xcodegen generate && xcodebuild test -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:GhostCrabTests/OnboardingViewModelTests -quiet
```
Expected: the build fails with `value of type 'OnboardingViewModel' has no member 'loadTask'`, then `** TEST FAILED **`.

- [ ] **Step 3: Rewrite the ViewModel**

Replace the whole content of `ios/GhostCrab/UI/Onboarding/OnboardingViewModel.swift`:

```swift
import Foundation
import Observation

/// ViewModel for the multi-step onboarding walkthrough.
///
/// Mirrors `OnboardingViewModel.kt`. Persists progress through `OnboardingRepository`
/// so the user can resume after backgrounding. Navigation between steps is linear;
/// `.completed` is the terminal state that signals the host to navigate to the
/// connection picker.
///
/// Step changes apply synchronously; persistence runs in one tracked
/// ``transitionTask``. While it is in flight, further `next()` / `back()` /
/// `skip()` calls are ignored, so a double tap moves exactly one step and
/// `markCompleted()` runs once. Hydration of the saved step never overrides a
/// step the user already chose.
@MainActor
@Observable
public final class OnboardingViewModel {

    // MARK: - Dependencies

    private let repository: any OnboardingRepository

    // MARK: - State

    /// The current visible step. Starts at `.welcome`; hydrated from repository on load.
    public private(set) var step: OnboardingStep = .welcome

    /// `true` once `markCompleted()` has finished running. The host view observes
    /// this and pushes `.connectionPicker` (matching the Android `onDone` callback).
    public private(set) var isFinished: Bool = false

    // MARK: - Tasks

    /// Hydration of the saved step. Read-only outside the VM so tests can await it.
    @ObservationIgnored private(set) var loadTask: Task<Void, Never>?

    /// In-flight persistence of the latest transition. Read-only outside the VM so tests can await it.
    @ObservationIgnored private(set) var transitionTask: Task<Void, Never>?

    /// Set by the first user transition; hydration then leaves ``step`` alone.
    @ObservationIgnored private var userHasNavigated: Bool = false

    // MARK: - Init

    public init(repository: any OnboardingRepository) {
        self.repository = repository
        self.loadTask = Task { await self.loadSavedStep() }
    }

    // MARK: - Actions

    /// Advances to the next step in the linear sequence, persisting the change.
    /// At `.findOnNetwork`, `next()` triggers completion. Ignored while a transition is in flight.
    public func next() {
        guard transitionTask == nil, !isFinished else { return }
        userHasNavigated = true
        let target = forward(from: step)
        step = target
        transitionTask = Task {
            await self.repository.saveStep(target)
            if target == .completed { await self.finish() }
            self.transitionTask = nil
        }
    }

    /// Returns to the previous step, persisting the change. No-op at `.welcome`.
    /// Ignored while a transition is in flight.
    public func back() {
        guard transitionTask == nil, !isFinished else { return }
        userHasNavigated = true
        let target = backward(from: step)
        step = target
        transitionTask = Task {
            await self.repository.saveStep(target)
            self.transitionTask = nil
        }
    }

    /// Skips directly to completion. Marks onboarding done once, however often it is tapped.
    public func skip() {
        guard transitionTask == nil, !isFinished else { return }
        userHasNavigated = true
        transitionTask = Task {
            await self.finish()
            self.transitionTask = nil
        }
    }

    /// Marks onboarding as completed in repository and flips `isFinished`.
    private func finish() async {
        self.step = .completed
        await self.repository.markCompleted()
        self.isFinished = true
    }

    // MARK: - Hydration

    private func loadSavedStep() async {
        let saved = await self.repository.getSavedStep()
        guard !userHasNavigated, saved != .completed else { return }
        self.step = saved
    }

    // MARK: - Pure transitions (mirror Kotlin `advance()` blocks)

    private func forward(from current: OnboardingStep) -> OnboardingStep {
        switch current {
        case .welcome:        return .whatIsOpenClaw
        case .whatIsOpenClaw: return .installGateway
        case .installGateway: return .startGateway
        case .startGateway:   return .verifyRunning
        case .verifyRunning:  return .findOnNetwork
        case .findOnNetwork:  return .completed
        case .completed:      return .completed
        }
    }

    private func backward(from current: OnboardingStep) -> OnboardingStep {
        switch current {
        case .welcome:        return .welcome
        case .whatIsOpenClaw: return .welcome
        case .installGateway: return .whatIsOpenClaw
        case .startGateway:   return .installGateway
        case .verifyRunning:  return .startGateway
        case .findOnNetwork:  return .verifyRunning
        case .completed:      return .findOnNetwork
        }
    }
}
```

- [ ] **Step 4: Run the tests, plus the UI smoke test (it taps Skip)** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

```bash
cd ios && xcodegen generate && xcodebuild test -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:GhostCrabTests/OnboardingViewModelTests -only-testing:GhostCrabUITests/LaunchSmokeTests -quiet
```
Expected: `** TEST SUCCEEDED **`.

- [ ] **Step 5: Commit**

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add ios/GhostCrab/UI/Onboarding/OnboardingViewModel.swift ios/GhostCrabTests/UI/OnboardingViewModelTests.swift
git commit -m "fix(ios): make onboarding transitions single-flight" -m "next/back/skip each spawned an untracked Task, so double taps could skip pages or mark completion twice, and late hydration could yank the user back. One tracked transition task with early return; hydration yields to user navigation."
```

---

### Task 21: In-flight guards for Dashboard and ConfigEditor

**Files:**
- Modify: `ios/GhostCrab/UI/Dashboard/DashboardViewModel.swift:51-91` (tasks, lifecycle, disconnect), `:113-126` (`loadActiveModel`)
- Modify: `ios/GhostCrab/UI/Dashboard/DashboardScreen.swift:48-54` (power button), `:57` (`.task`), `:358-361` (`disconnectAndPop`). The status-bar disconnect at `:27` keeps calling `disconnectAndPop()`; the VM guard covers it.
- Modify: `ios/GhostCrab/UI/Config/ConfigEditorViewModel.swift:28` (state), `:66-69` (`stop`), `:82-99` (`loadConfig`), `:187-235` (`confirmSave`)
- Test: `ios/GhostCrabTests/UI/DashboardViewModelTests.swift`, `ios/GhostCrabTests/UI/ConfigEditorViewModelTests.swift`

- [ ] **Step 1: Write the failing tests**

Create `ios/GhostCrabTests/UI/DashboardViewModelTests.swift`:

```swift
import Testing
@testable import GhostCrab

@MainActor
@Suite("DashboardViewModel")
struct DashboardViewModelTests {

    @Test func secondDisconnectIsIgnored() async {
        let gateway = FakeGatewayConnectionManager()
        let vm = DashboardViewModel(gateway: gateway, models: FakeModelRepository(models: []), ai: FakeAIRecommendationService())

        let first = await vm.disconnect()
        let second = await vm.disconnect()

        #expect(first)
        #expect(!second)
        #expect(gateway.disconnectCount == 1)
    }

    @Test func repeatedAppearDoesNotStackLoads() async {
        let models = FakeModelRepository(models: [FakeModelRepository.model("m1", active: true)])
        let vm = DashboardViewModel(gateway: FakeGatewayConnectionManager(), models: models, ai: FakeAIRecommendationService(available: true))

        vm.onAppear()
        vm.onAppear()
        await vm.loadTask?.value
        await vm.aiTask?.value

        #expect(models.getModelsCount == 1)
        #expect(vm.activeModel?.id == "m1")
        #expect(vm.aiAvailable)
    }
}
```

Create `ios/GhostCrabTests/UI/ConfigEditorViewModelTests.swift`:

```swift
import Testing
@testable import GhostCrab

@MainActor
@Suite("ConfigEditorViewModel")
struct ConfigEditorViewModelTests {

    @Test func secondConfirmWhileSavingIsIgnored() async {
        let repo = FakeConfigRepository(config: OpenClawConfig(sections: ["gateway": AnyCodable(["port": 18789])]))
        let vm = ConfigEditorViewModel(config: repo, gateway: FakeGatewayConnectionManager())
        vm.loadConfig()
        await vm.loadTask?.value
        vm.editSection("gateway", value: AnyCodable(["port": 18790]))

        vm.confirmSave("gateway")
        vm.confirmSave("gateway")
        await vm.saveTask?.value

        #expect(repo.updatedSections == ["gateway"])
    }

    @Test func repeatedLoadWhileLoadingIsIgnored() async {
        let repo = FakeConfigRepository(config: OpenClawConfig(sections: ["gateway": AnyCodable(["port": 18789])]))
        let vm = ConfigEditorViewModel(config: repo, gateway: FakeGatewayConnectionManager())

        vm.loadConfig()
        let first = vm.loadTask
        vm.loadConfig()

        #expect(first != nil)
        #expect(vm.loadTask == first)
        await vm.loadTask?.value
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

```bash
cd ios && xcodegen generate && xcodebuild test -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:GhostCrabTests/DashboardViewModelTests -only-testing:GhostCrabTests/ConfigEditorViewModelTests -quiet
```
Expected: the build fails with `value of type 'DashboardViewModel' has no member 'loadTask'`, then `** TEST FAILED **`.

- [ ] **Step 3: Guard `DashboardViewModel`**

Replace the `// MARK: - Internal observation tasks` block (lines 51-53):

```swift
    // MARK: - Internal tasks

    private var connectionObservationTask: Task<Void, Never>?

    /// In-flight model load from `onAppear`. Read-only outside the VM so tests can await it.
    @ObservationIgnored private(set) var loadTask: Task<Void, Never>?

    /// In-flight AI availability probe. Read-only outside the VM so tests can await it.
    @ObservationIgnored private(set) var aiTask: Task<Void, Never>?

    /// `true` once the user asked to disconnect; repeat taps are ignored.
    public private(set) var isDisconnecting: Bool = false
```

Replace `onAppear()` and `disconnect()` (lines 74-91):

```swift
    /// Call from the screen's `.task` modifier. Starts observing the connection
    /// stream, loads the active model, and probes AI availability. Loads that
    /// are already running are not started again.
    public func onAppear() {
        startObservingConnection()
        if loadTask == nil {
            loadTask = Task {
                await self.loadActiveModel()
                if !Task.isCancelled { self.loadTask = nil }
            }
        }
        if aiTask == nil {
            aiTask = Task {
                await self.probeAIAvailability()
                if !Task.isCancelled { self.aiTask = nil }
            }
        }
    }

    /// Call from the screen's `.onDisappear` (e.g. pushing Models / Config).
    /// Cancels in-flight work; `onAppear()` restarts it on return.
    public func onDisappear() {
        connectionObservationTask?.cancel()
        connectionObservationTask = nil
        loadTask?.cancel()
        loadTask = nil
        aiTask?.cancel()
        aiTask = nil
    }

    /// Manual refresh, invoked by `.refreshable`. Reloads the model list.
    public func refresh() async {
        await loadActiveModel()
    }

    /// Disconnects from the gateway once. The caller pops the nav stack only
    /// when this returns `true`, so a double tap cannot dismiss twice.
    ///
    /// - Returns: `true` for the call that disconnected; `false` for repeats.
    public func disconnect() async -> Bool {
        guard !isDisconnecting else { return false }
        isDisconnecting = true
        await gateway.disconnect()
        return true
    }
```

Replace `loadActiveModel()` so a cancelled load does not post an error banner (URLSession reports cancellation as `URLError.cancelled`, which the client maps to `.unreachable`):

```swift
    private func loadActiveModel() async {
        isLoading = true
        defer { isLoading = false }
        do {
            let list = try await models.getModels()
            guard !Task.isCancelled else { return }
            self.modelCount = list.count
            self.activeModel = list.first(where: { $0.isActive })
            self.lastError = nil
        } catch {
            guard !Task.isCancelled else { return }
            if let gatewayError = error as? GatewayError {
                self.lastError = describe(error: gatewayError)
            } else {
                self.lastError = "\(type(of: error)): \(error.localizedDescription)"
            }
        }
    }
```

- [ ] **Step 4: Update `DashboardScreen`**

Replace `.task { vm.onAppear() }` (line 57):

```swift
        .task { vm.onAppear() }
        .onDisappear { vm.onDisappear() }
```

Add `.disabled(vm.isDisconnecting)` to the toolbar power button, after `.accessibilityLabel("Disconnect")`:

```swift
                .accessibilityLabel("Disconnect")
                .disabled(vm.isDisconnecting)
```

Replace `disconnectAndPop()` (lines 358-361):

```swift
    private func disconnectAndPop() async {
        if await vm.disconnect() {
            dismiss()
        }
    }
```

- [ ] **Step 5: Guard `ConfigEditorViewModel`**

Add below `private var connectionTask: Task<Void, Never>?` (line 28):

```swift

    /// In-flight config load. Read-only outside the VM so tests can await it.
    @ObservationIgnored private(set) var loadTask: Task<Void, Never>?

    /// In-flight section save. Read-only outside the VM so tests can await it.
    @ObservationIgnored private(set) var saveTask: Task<Void, Never>?
```

Replace `stop()`:

```swift
    /// Cancel background subscriptions and an in-flight load. Call from `.task` cancellation.
    /// An in-flight save is left to finish so the gateway write is not abandoned mid-way.
    public func stop() {
        connectionTask?.cancel()
        connectionTask = nil
        loadTask?.cancel()
        loadTask = nil
    }
```

Replace `loadConfig()` (keep its doc comment and add the last line shown):

```swift
    /// Ignored while a load is already in flight.
    public func loadConfig() {
        guard loadTask == nil else { return }
        loadTask = Task { [weak self] in
            guard let self else { return }
            defer { if !Task.isCancelled { self.loadTask = nil } }
            self.state = .loading
            do {
                let config = try await self.configRepository.getConfig()
                if config.sections.isEmpty {
                    self.state = .noConfigApi
                } else {
                    self.state = .ready(config: config)
                }
            } catch let e as GatewayError {
                self.state = .error(message: e.errorDescription ?? "Unknown error")
            } catch {
                self.state = .error(message: error.localizedDescription)
            }
        }
    }
```

In `confirmSave(_:)`, replace the opening guard:

```swift
        guard case .ready(_, let pending, _, _, _, _) = state,
              let newValue = pending[section] else {
            return
        }
```
with:
```swift
        guard saveTask == nil,
              case .ready(_, let pending, _, _, _, _) = state,
              let newValue = pending[section] else {
            return
        }
```

Then change `Task { [weak self] in` (the save task) to `saveTask = Task { [weak self] in`, and insert `defer { self.saveTask = nil }` as the line right after its `guard let self else { return }`. Add `/// Ignored while a save is already in flight.` to its doc comment.

- [ ] **Step 6: Run the tests to verify they pass** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

```bash
cd ios && xcodegen generate && xcodebuild test -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:GhostCrabTests/DashboardViewModelTests -only-testing:GhostCrabTests/ConfigEditorViewModelTests -quiet
```
Expected: `** TEST SUCCEEDED **`.

- [ ] **Step 7: Commit**

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add ios/GhostCrab/UI/Dashboard/DashboardViewModel.swift ios/GhostCrab/UI/Dashboard/DashboardScreen.swift ios/GhostCrab/UI/Config/ConfigEditorViewModel.swift ios/GhostCrabTests/UI/DashboardViewModelTests.swift ios/GhostCrabTests/UI/ConfigEditorViewModelTests.swift
git commit -m "fix(ios): track Dashboard and Config editor tasks" -m "Dashboard re-appear stacked model/AI loads and disconnect could dismiss twice; Config editor could fire duplicate loads and saves. Tasks are tracked with early-return guards, Dashboard cancels on disappear, and disconnect is single-shot."
```

---

### Task 22: In-flight guards for AI recommendations and Settings

**Files:**
- Modify: `ios/GhostCrab/UI/AI/AIRecommendationViewModel.swift` (state, `submit`, `confirmApplyPatch`, `cancelInFlight`)
- Modify: `ios/GhostCrab/UI/AI/AIRecommendationScreen.swift:39-41`
- Modify: `ios/GhostCrab/UI/Settings/SettingsViewModel.swift:59-81`
- Test: `ios/GhostCrabTests/UI/AIRecommendationViewModelTests.swift`, `ios/GhostCrabTests/UI/SettingsViewModelTests.swift`

Settings needs ordering, not dropping. Each toggle flip spawned an independent `Task`, so a slow first write could land after a fast second one and persist the stale value while the UI showed the new one. Writes are now chained, so the last tap wins.

- [ ] **Step 1: Write the failing tests**

Create `ios/GhostCrabTests/UI/AIRecommendationViewModelTests.swift`:

```swift
import Testing
@testable import GhostCrab

@MainActor
@Suite("AIRecommendationViewModel")
struct AIRecommendationViewModelTests {

    @Test func doubleApplyPatchWritesOnce() async {
        let change = SuggestedChange(section: "gateway", key: "port", currentValue: "18789", suggestedValue: "18790")
        let config = FakeConfigRepository(config: OpenClawConfig(sections: [:]))
        let vm = AIRecommendationViewModel(
            ai: FakeAIRecommendationService(available: true, suggestedChanges: [change]),
            gateway: FakeGatewayConnectionManager(),
            config: config,
            models: FakeModelRepository(models: [])
        )
        vm.prompt = "faster port?"
        vm.submit()
        await vm.submitTask?.value
        #expect(vm.hasSuggestedPatch)

        vm.applySuggestedPatch()
        vm.confirmApplyPatch()
        vm.confirmApplyPatch()
        await vm.applyTask?.value

        #expect(config.updatedSections == ["gateway"])
        #expect(!vm.isPresentingPatch)
    }

    @Test func cancelInFlightClearsLoading() {
        let vm = AIRecommendationViewModel(
            ai: FakeAIRecommendationService(),
            gateway: FakeGatewayConnectionManager(),
            config: FakeConfigRepository(config: OpenClawConfig(sections: [:])),
            models: FakeModelRepository(models: [])
        )
        vm.prompt = "anything"
        vm.submit()
        #expect(vm.loading)

        vm.cancelInFlight()

        #expect(!vm.loading)
        #expect(vm.submitTask == nil)
    }
}
```

Create `ios/GhostCrabTests/UI/SettingsViewModelTests.swift`:

```swift
import Testing
@testable import GhostCrab

@MainActor
@Suite("SettingsViewModel")
struct SettingsViewModelTests {

    @Test func rapidTogglesPersistInTapOrder() async {
        // The first write is slow, so unchained writes would land as [false, true].
        let settings = FakeSettingsRepository(writeDelays: [.milliseconds(80), .zero])
        let vm = SettingsViewModel(settings: settings, onboarding: FakeOnboardingRepository())

        vm.setAllowCleartextPublicIPs(true)
        vm.setAllowCleartextPublicIPs(false)
        await vm.writeTask?.value

        #expect(settings.writeLog == [true, false])
        #expect(!vm.allowCleartextPublicIPs)
    }

    @Test func doubleResetResetsOnce() async {
        let onboarding = FakeOnboardingRepository(completed: true)
        let vm = SettingsViewModel(settings: FakeSettingsRepository(), onboarding: onboarding)

        vm.resetOnboarding()
        vm.resetOnboarding()
        await vm.resetTask?.value

        #expect(onboarding.resetCount == 1)
        #expect(vm.onboardingResetSuccess)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

```bash
cd ios && xcodegen generate && xcodebuild test -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:GhostCrabTests/AIRecommendationViewModelTests -only-testing:GhostCrabTests/SettingsViewModelTests -quiet
```
Expected: the build fails with `value of type 'AIRecommendationViewModel' has no member 'submitTask'`, then `** TEST FAILED **`.

- [ ] **Step 3: Guard `AIRecommendationViewModel`**

Add below `public var isPresentingPatch: Bool = false`:

```swift

    /// In-flight recommendation request. Read-only outside the VM so tests can await it.
    @ObservationIgnored private(set) var submitTask: Task<Void, Never>?

    /// In-flight patch write. Read-only outside the VM so tests can await it.
    @ObservationIgnored private(set) var applyTask: Task<Void, Never>?
```

In `submit()`, replace the `Task { [weak self] in … }` block (the guard `!loading` above it stays):

```swift
        submitTask = Task { [weak self] in
            guard let self else { return }
            defer { if !Task.isCancelled { self.submitTask = nil } }
            let context = await self.buildContext()
            do {
                let result = try await self.ai.getRecommendation(query: trimmed, context: context)
                guard !Task.isCancelled else { return }
                self.response = result
                self.loading = false
            } catch let error as GatewayError {
                guard !Task.isCancelled else { return }
                self.lastError = AIRecommendationError(gatewayError: error)
                self.response = nil
                self.loading = false
            } catch {
                guard !Task.isCancelled else { return }
                self.lastError = .other(message: error.localizedDescription)
                self.response = nil
                self.loading = false
            }
        }
```

Replace `confirmApplyPatch()`:

```swift
    /// Persists all suggested changes from the latest response back to the
    /// gateway via ``ConfigRepository/updateConfig(section:value:)``.
    ///
    /// On success: closes the patch sheet and leaves the response on screen
    /// for context. On any failure: surfaces ``lastError`` and keeps the sheet open
    /// so the user can retry. Ignored while a write is already in flight.
    public func confirmApplyPatch() {
        guard applyTask == nil else { return }
        guard let response, !response.suggestedChanges.isEmpty else {
            isPresentingPatch = false
            return
        }
        applyTask = Task { [weak self, config] in
            guard let self else { return }
            defer { self.applyTask = nil }
            do {
                for change in response.suggestedChanges {
                    // Build a JSON merge-patch: `{ <key>: <suggestedValue> }`.
                    let patch = AnyCodable([change.key: AnyCodable(Self.parseJSONValue(change.suggestedValue))])
                    try await config.updateConfig(section: change.section, value: patch)
                }
                self.isPresentingPatch = false
            } catch let error as GatewayError {
                self.lastError = AIRecommendationError(gatewayError: error)
            } catch {
                self.lastError = .other(message: error.localizedDescription)
            }
        }
    }

    /// Cancels an in-flight recommendation when the screen goes away. Patch
    /// writes are left to finish; aborting between sections would leave the
    /// config half-applied.
    public func cancelInFlight() {
        submitTask?.cancel()
        submitTask = nil
        loading = false
    }
```

In `ios/GhostCrab/UI/AI/AIRecommendationScreen.swift`, replace lines 39-41:

```swift
        .onAppear {
            if vm == nil { vm = container.makeAIRecommendationVM() }
        }
        .onDisappear {
            vm?.cancelInFlight()
        }
```

- [ ] **Step 4: Serialise Settings writes**

In `ios/GhostCrab/UI/Settings/SettingsViewModel.swift`, add below `private let onboarding: any OnboardingRepository`:

```swift

    // MARK: - Tasks

    /// Latest preference write; each write awaits the previous one. Read-only outside the VM so tests can await it.
    @ObservationIgnored private(set) var writeTask: Task<Void, Never>?

    /// In-flight onboarding reset. Read-only outside the VM so tests can await it.
    @ObservationIgnored private(set) var resetTask: Task<Void, Never>?
```

Replace `setAllowCleartextPublicIPs(_:)` and `resetOnboarding()`:

```swift
    /// Persists the new value of the cleartext preference.
    ///
    /// Writes are chained (each awaits the previous), so rapid toggling
    /// persists values in tap order and the last tap wins.
    ///
    /// - Parameter enabled: `true` to allow HTTP connections to public IPs.
    public func setAllowCleartextPublicIPs(_ enabled: Bool) {
        // Optimistic local update for snappier toggle UX; the stream will reconfirm.
        self.allowCleartextPublicIPs = enabled
        let previous = writeTask
        writeTask = Task { [settings] in
            await previous?.value
            await settings.setAllowCleartextPublicIPs(enabled)
        }
    }

    /// Resets onboarding persistence so the walkthrough shows again on next launch.
    ///
    /// Sets ``onboardingResetSuccess`` to `true` on completion so the view can
    /// surface a confirmation and then optionally route back to onboarding.
    /// Ignored while a reset is already in flight.
    public func resetOnboarding() {
        guard resetTask == nil else { return }
        resetTask = Task { [weak self, onboarding] in
            await onboarding.reset()
            self?.onboardingResetSuccess = true
            self?.resetTask = nil
        }
    }
```

Also update the class doc line `/// stream launched from the view's `.task` modifier — never own a `Task` here.` to:

```swift
/// stream launched from the view's `.task` modifier. Writes and the reset run
/// in tracked tasks (``writeTask``, ``resetTask``).
```

- [ ] **Step 5: Run the tests to verify they pass** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

```bash
cd ios && xcodegen generate && xcodebuild test -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:GhostCrabTests/AIRecommendationViewModelTests -only-testing:GhostCrabTests/SettingsViewModelTests -quiet
```
Expected: `** TEST SUCCEEDED **`.

- [ ] **Step 6: Run the full suite** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

```bash
cd ios && xcodebuild test -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=iOS Simulator,name=iPhone 16' -quiet
```
Expected: `** TEST SUCCEEDED **` (all unit suites plus `LaunchSmokeTests`).

- [ ] **Step 7: Commit**

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add ios/GhostCrab/UI/AI/AIRecommendationViewModel.swift ios/GhostCrab/UI/AI/AIRecommendationScreen.swift ios/GhostCrab/UI/Settings/SettingsViewModel.swift ios/GhostCrabTests/UI/AIRecommendationViewModelTests.swift ios/GhostCrabTests/UI/SettingsViewModelTests.swift
git commit -m "fix(ios): track AI and Settings tasks; persist toggles in tap order" -m "AI submit/apply now run in tracked tasks (double apply writes once; leaving the screen cancels a pending recommendation). Settings chains preference writes so a slow write can no longer land after a newer one, and onboarding reset is single-shot."
```

---

### Task 23: One TestFlight upload path, and a key wipe that works on macOS

**Files:**
- Modify: `ios/ExportOptions.plist` (whole file)
- Modify: `.github/workflows/ios-release.yml:25` (runner), `:175-179` (wipe)

Decision: the workflow uploads with `altool`, using the explicit API-key secrets, and `ExportOptions` only exports. With `destination=upload`, `xcodebuild -exportArchive` uploads during export itself and writes no `GhostCrab.ipa`, so the following `altool` step had nothing to upload. `compileBitcode` has been obsolete since Xcode 14. `manageAppVersionAndBuildNumber` is set to `false` so the CI-provided `CURRENT_PROJECT_VERSION` (the run number) stays authoritative. On the key wipe: macOS has no `shred`, so `shred -u … || rm -f …` only ever reached its `rm -f` fallback. The fallback did delete the key, but the `shred` call was dead code. On APFS (copy-on-write, SSD) overwriting before deleting gives no extra guarantee, so the step now does a plain `rm -f` and asserts the file is gone.

- [ ] **Step 1: Rewrite `ExportOptions.plist`**

Replace the whole content of `ios/ExportOptions.plist`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>method</key>
    <string>app-store-connect</string>

    <key>teamID</key>
    <string>QJW4S8BDFX</string>

    <!-- Export only. ios-release.yml uploads the .ipa/.pkg with altool and the ASC API key. -->
    <key>destination</key>
    <string>export</string>

    <key>signingStyle</key>
    <string>automatic</string>

    <key>stripSwiftSymbols</key>
    <true/>

    <key>uploadSymbols</key>
    <true/>

    <!-- CI sets CURRENT_PROJECT_VERSION (run number); do not let Xcode rewrite it. -->
    <key>manageAppVersionAndBuildNumber</key>
    <false/>
</dict>
</plist>
```

- [ ] **Step 2: Fix the release workflow**

In `.github/workflows/ios-release.yml`, change line 25 from `runs-on: [self-hosted, macOS, ARM64]` to:

```yaml
    runs-on: [self-hosted, macOS, mac-mini-ios]
```

Replace the `Wipe ASC API key` step (lines 175-179):

```yaml
      - name: Wipe ASC API key
        if: always()
        run: |
          # macOS has no shred(1). On APFS (copy-on-write SSD) an in-place
          # overwrite gives no extra guarantee, so unlink and verify.
          KEY_FILE="$HOME/.appstoreconnect/private_keys/AuthKey_${{ secrets.ASC_KEY_ID }}.p8"
          rm -f "$KEY_FILE"
          if [ -e "$KEY_FILE" ]; then
            echo "::error::ASC API key still present at $KEY_FILE"
            exit 1
          fi
```

- [ ] **Step 3: Lint the plist** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

```bash
plutil -lint ios/ExportOptions.plist
```
Expected: `ios/ExportOptions.plist: OK`.

- [ ] **Step 4: Commit**

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add ios/ExportOptions.plist .github/workflows/ios-release.yml
git commit -m "ci(ios): export-then-altool upload, roster runner label, working key wipe" -m "ExportOptions destination=upload uploaded during export and left no .ipa for the altool step; it now only exports. Drops obsolete compileBitcode and lets CI own the build number. Runs on mac-mini-ios and replaces the dead shred call with rm -f plus a presence check."
```

- [ ] **Step 5: First TestFlight run** ⚠ manual (after the PR merges; needs App Store Connect access)

Complete the unchecked items in `docs/IOS_RELEASE_CHECKLIST.md`: the ASC app record (name, SKU, bundle `com.qavren.ghostcrab`), the privacy URL, categories, age rating, TestFlight test info, an ASC API key, and the repo secrets `ASC_KEY_ID`, `ASC_ISSUER_ID` and `ASC_KEY_P8`. Then:

```bash
gh workflow run ios-release.yml --ref main -f skip_macos=true
gh run watch "$(gh run list --workflow ios-release.yml --limit 1 --json databaseId -q '.[0].databaseId')"
```
Expected: the job is green on `mac-mini-ios`, a `ghostcrab-build-<run>` artifact contains `GhostCrab.ipa`, and the build appears in App Store Connect under TestFlight with no ITMS-91053 privacy-manifest warning.

---

### Task 24: PR CI lane for iOS on the Mac Mini

**Files:**
- Create: `.github/workflows/ios-pr.yml`

This stays inside the repo's Actions-spend rules: it runs on the self-hosted runner (free minutes), only on `pull_request`, filtered to `ios/**`, and cancels superseded runs. The simulator is picked at runtime from what the runner's Xcode has installed (iPhone 16 on Xcode 16, iPhone 17 on Xcode 26). If the runner ever has several runtimes and you need a specific one, replace the pick step with a fixed `-destination 'platform=iOS Simulator,name=iPhone 16'`.

- [ ] **Step 1: Create the workflow**

Create `.github/workflows/ios-pr.yml`:

```yaml
name: iOS PR

on:
  pull_request:
    paths:
      - "ios/**"
      - ".github/workflows/ios-pr.yml"

permissions:
  contents: read

concurrency:
  group: ios-pr-${{ github.event.pull_request.number }}
  cancel-in-progress: true

jobs:
  test:
    name: Unit + UI tests (simulator)
    # Mac Mini self-hosted runner: free minutes. Hosted macOS minutes are what the invoice is.
    runs-on: [self-hosted, macOS, mac-mini-ios]
    timeout-minutes: 45
    env:
      DEVELOPER_DIR: /Applications/Xcode.app/Contents/Developer
    steps:
      - name: Checkout
        uses: actions/checkout@v7

      - name: Install xcodegen if missing
        run: |
          if ! command -v xcodegen >/dev/null 2>&1; then
            brew install xcodegen
          fi

      - name: Generate Xcode project
        working-directory: ios
        run: xcodegen generate

      - name: Pick an iPhone simulator
        id: sim
        run: |
          UDID=$(xcrun simctl list devices available | grep -E '^[[:space:]]+iPhone' | head -n1 | sed -E 's/.*\(([0-9A-F-]{36})\).*/\1/')
          if [ -z "$UDID" ]; then
            echo "::error::No available iPhone simulator on this runner"
            xcrun simctl list devices available
            exit 1
          fi
          echo "Using simulator $UDID"
          echo "udid=$UDID" >> "$GITHUB_OUTPUT"

      - name: Test
        working-directory: ios
        run: |
          xcodebuild test \
            -project GhostCrab.xcodeproj \
            -scheme GhostCrab \
            -destination "platform=iOS Simulator,id=${{ steps.sim.outputs.udid }}" \
            -resultBundlePath "$RUNNER_TEMP/GhostCrab.xcresult" \
            -skipMacroValidation \
            -quiet

      - name: Upload test results
        if: failure()
        uses: actions/upload-artifact@v7
        with:
          name: ios-pr-xcresult-${{ github.event.pull_request.number }}-${{ github.run_attempt }}
          path: ${{ runner.temp }}/GhostCrab.xcresult
          retention-days: 7
```

- [ ] **Step 2: Commit and push, then watch the first run** ⚠ manual (needs the pushed branch with an open PR)

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add .github/workflows/ios-pr.yml
git commit -m "ci(ios): add PR test lane on the Mac Mini runner" -m "Runs xcodegen + xcodebuild test (unit + UI smoke) on pull_request for ios/** changes, on the self-hosted mac-mini-ios runner, with cancel-in-progress and the xcresult uploaded on failure."
git push -u origin feat/ios-finish
gh pr create --fill --base main
gh run watch "$(gh run list --workflow ios-pr.yml --limit 1 --json databaseId -q '.[0].databaseId')"
```
Expected: the `iOS PR / Unit + UI tests (simulator)` check goes green on the PR.

---

### Task 25: Mirror the Android version: `MARKETING_VERSION` 0.2.0

**Files:**
- Modify: `ios/project.yml:17`

The spec (`docs/superpowers/specs/2026-05-28-ios-app-design.md`, "Required Info.plist keys") says `CFBundleShortVersionString` mirrors Android's `versionName`. The Android plan bumps that to `0.2.0`, so iOS moves from `1.0.0` to `0.2.0`. `CURRENT_PROJECT_VERSION` stays as it is: CI overrides it with the run number, because TestFlight needs build numbers that only go up. This is a deliberate departure from the spec's "mirror versionCode" line.

- [ ] **Step 1: Bump the version**

In `ios/project.yml`, change line 17 from `MARKETING_VERSION: "1.0.0"` to:

```yaml
    MARKETING_VERSION: "0.2.0"  # mirrors Android versionName (app/build.gradle.kts)
```

- [ ] **Step 2: Verify the build setting** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

```bash
cd ios && xcodegen generate && xcodebuild -showBuildSettings -project GhostCrab.xcodeproj -scheme GhostCrab 2>/dev/null | grep -E '^\s*MARKETING_VERSION'
```
Expected: `MARKETING_VERSION = 0.2.0`. Settings → About in the app then shows `0.2.0 (<build>) · <sha>`.

- [ ] **Step 3: Commit**

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add ios/project.yml
git commit -m "chore(ios): set MARKETING_VERSION to 0.2.0 to mirror Android" -m "Spec: CFBundleShortVersionString mirrors Android versionName, which moves to 0.2.0. Build numbers stay CI-owned (run number)."
```

---

## Coverage map

| Source item | Task(s) |
|---|---|
| Audit #1: PrivacyInfo.xcprivacy | 11 |
| Audit #2: bundle fonts + UIAppFonts | 10 |
| Audit #3: QR token (VM → handoff, not Route), URL scheme, `.onOpenURL` | 5, 6, 7, 8 |
| Audit #4: model confirm + in-flight guards | 9, 19, 20 |
| Audit #5: ExportOptions vs altool, key wipe, first TestFlight | 23 (Step 5 ⚠ manual) |
| Audit #6: iOS PR CI lane | 1 (clean-checkout fix), 24 |
| Audit #7: tests (API client via MockURLProtocol, VMs, QR decode, UI test target) | 2, 5–9, 13–22, 1 |
| Audit #8: per-request timeouts, per-client guard, ATS, `try?` swallowing | 17, 18, 12, 13, 14, 16 |
| Audit #9: lazy VMs, PendingScreen, AppContainerKey | 3, 4 |
| Audit P2: untracked `Task {}` sites (Picker, ManualEntry, Onboarding, Dashboard incl. screen, Config, AI, Settings, ModelManager screen) | 19, 20, 21, 22, 9 |
| Version mirrors Android | 25 |

Out of scope (still open after this plan): auto-resume of the last profile at launch (audit #9); a generic `request<T>` helper, `Mutex` replacing `@unchecked Sendable`, a String Catalog, splitting the 300–490-line screens, and `.seconds()` constants for `Task.sleep` (audit #10, P3); dropping the `com.apple.security.network.server` entitlement (needs a Catalyst Bonjour test first); `QrScanScreen` `try? scanner.startScanning()` and `ConfigEditorScreen` `try? prettyPrint` (P3); gating `ios-release.yml` on the PR lane.
