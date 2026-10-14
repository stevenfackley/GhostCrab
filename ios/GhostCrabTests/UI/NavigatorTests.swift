import Testing
import Foundation
@testable import GhostCrab

/// Pins the stack rules: "go to the picker" and "go to the dashboard" replace
/// the stack instead of pushing, so no second picker or dashboard (with its
/// live VM) piles up under the first.
@MainActor
@Suite("Navigator")
struct NavigatorTests {

    @Test func startScreenIsSetOnce() {
        let navigator = Navigator()
        #expect(navigator.root == nil)

        navigator.resolveRoot(.onboarding)
        navigator.showPicker()
        navigator.resolveRoot(.onboarding)

        #expect(navigator.root == .connectionPicker)
    }

    @Test func pushAppendsOnTop() {
        let navigator = Navigator(root: .connectionPicker)
        navigator.push(.dashboard)
        navigator.push(.configEditor)
        #expect(navigator.path == [.dashboard, .configEditor])
    }

    @Test func pickerFromAPushedScreenPopsToTheRootPicker() {
        // Config Editor's "Pick a gateway" when the connection dropped.
        let navigator = Navigator(root: .connectionPicker)
        navigator.push(.dashboard)
        navigator.push(.configEditor)

        navigator.showPicker()

        #expect(navigator.root == .connectionPicker)
        #expect(navigator.path.isEmpty)
    }

    @Test func finishingFirstRunOnboardingReplacesItWithThePicker() {
        let navigator = Navigator(root: .onboarding)

        navigator.showPicker()

        #expect(navigator.root == .connectionPicker)
        #expect(navigator.path.isEmpty)
    }

    @Test func onboardingReplayedFromSettingsEndsOnASinglePicker() {
        let navigator = Navigator(root: .connectionPicker)
        navigator.push(.dashboard)
        navigator.push(.settings)
        navigator.push(.onboarding)

        navigator.showPicker()

        #expect(navigator.root == .connectionPicker)
        #expect(navigator.path.isEmpty)
    }

    @Test func dashboardAfterConnectSitsDirectlyOnThePicker() throws {
        let navigator = Navigator(root: .connectionPicker)
        navigator.push(.qrScan)
        navigator.push(.manualEntry(prefillURL: try #require(URL(string: "http://192.168.1.20:18789"))))

        navigator.showDashboard()

        #expect(navigator.root == .connectionPicker)
        #expect(navigator.path == [.dashboard])
    }

    @Test func dashboardAfterAFirstRunConnectReplacesOnboarding() {
        let navigator = Navigator(root: .onboarding)
        navigator.push(.manualEntry(prefillURL: nil))

        navigator.showDashboard()

        #expect(navigator.root == .connectionPicker)
        #expect(navigator.path == [.dashboard])
    }

    @Test func reconnectingFromAPushedScreenKeepsOneDashboard() {
        let navigator = Navigator(root: .connectionPicker)
        navigator.push(.dashboard)
        navigator.push(.configEditor)

        navigator.showPicker()
        navigator.showDashboard()

        #expect(navigator.path == [.dashboard])
    }

    @Test func navigateActionDrivesTheNavigator() {
        let navigator = Navigator(root: .connectionPicker)
        let navigate = NavigateAction(navigator)

        navigate(.dashboard)
        navigate(.settings)
        #expect(navigator.path == [.dashboard, .settings])

        navigate.toPicker()
        #expect(navigator.path.isEmpty)

        navigate.toDashboard()
        #expect(navigator.path == [.dashboard])
    }

    // MARK: - Pairing deep links

    @Test func manualEntryForALinkIsPushedOverOtherScreens() throws {
        let url = try #require(URL(string: "http://192.168.1.20:18789"))
        let navigator = Navigator(root: .connectionPicker)
        navigator.push(.dashboard)

        navigator.showManualEntry(prefillURL: url)

        #expect(navigator.path == [.dashboard, .manualEntry(prefillURL: url)])
        #expect(navigator.isShowingManualEntry(prefillURL: url))
    }

    @Test func manualEntryForALinkReplacesTheManualEntryOnTop() throws {
        let url = try #require(URL(string: "http://192.168.1.20:18789"))
        let navigator = Navigator(root: .connectionPicker)
        navigator.push(.manualEntry(prefillURL: nil))

        navigator.showManualEntry(prefillURL: url)

        #expect(navigator.path == [.manualEntry(prefillURL: url)])
    }
}
