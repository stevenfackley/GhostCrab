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
