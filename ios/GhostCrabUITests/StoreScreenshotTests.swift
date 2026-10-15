import XCTest

/// App Store screenshots, captured on the Mac Mini runner by `ios-screenshots.yml`.
///
/// Walks onboarding → gateway picker → LAN scan → QR scan → Manual Entry (pre-filled
/// through a `ghostcrab://pair` deep link) → dashboard → config editor → models →
/// skills → settings → picker with the saved gateway. There is no 09: OpenClaw has no
/// recommendation API, so the dashboard offers no AI tile (ADR-0007), and the other
/// files keep their numbers. The connected screens need the protocol-4 mock gateway
/// (`scripts/mock-gateway`, `AUTO_APPROVE=1` so the new simulator skips pairing) that
/// the workflow starts on the runner.
///
/// Skipped unless `GHOSTCRAB_STORE_SCREENSHOTS=1` reaches the test runner (the
/// workflow sets `TEST_RUNNER_GHOSTCRAB_STORE_SCREENSHOTS`), so `ios-pr.yml`, which
/// runs every UI test, never depends on a gateway.
///
/// Each screen is saved twice: as a kept `XCTAttachment` in the result bundle and,
/// when `GHOSTCRAB_SCREENSHOT_DIR` is set, as `<name>.png` in that host directory
/// (simulator processes can write to host paths). A screen that fails to appear is
/// recorded as a failure and the walk continues, so one broken step does not cost
/// the rest of the set.
///
/// Runner environment (all optional except the switch):
/// - `GHOSTCRAB_GATEWAY_URL`: gateway base URL, default `http://127.0.0.1:18790`.
/// - `GHOSTCRAB_GATEWAY_TOKEN`: gateway token the pairing link carries, default none.
/// - `GHOSTCRAB_GATEWAY_NAME`: suggested gateway name, default `Home Lab`.
final class StoreScreenshotTests: XCTestCase {

    @MainActor
    func testCaptureStoreScreenshots() throws {
        let env = ProcessInfo.processInfo.environment
        try XCTSkipUnless(
            env["GHOSTCRAB_STORE_SCREENSHOTS"] == "1",
            "Store screenshots run only from ios-screenshots.yml"
        )
        continueAfterFailure = true

        let outputDir = try makeOutputDir(env["GHOSTCRAB_SCREENSHOT_DIR"])
        let pairURL = try pairingLink(
            gatewayURL: env["GHOSTCRAB_GATEWAY_URL"] ?? "http://127.0.0.1:18790",
            token: env["GHOSTCRAB_GATEWAY_TOKEN"],
            name: env["GHOSTCRAB_GATEWAY_NAME"] ?? "Home Lab"
        )

        // System permission prompts (local network, camera) would cover the screen.
        addUIInterruptionMonitor(withDescription: "System permission alert") { alert in
            for label in ["Allow", "Allow While Using App", "OK"] where alert.buttons[label].exists {
                alert.buttons[label].tap()
                return true
            }
            return false
        }

        let app = XCUIApplication()
        // NSArgumentDomain overrides for this launch only: first-run onboarding.
        app.launchArguments += [
            "-com.qavren.ghostcrab.onboarding.completed", "NO",
            "-com.qavren.ghostcrab.onboarding.current_step", "welcome",
        ]
        app.launch()

        // 1. Onboarding
        if waitFor(app.buttons["Get Started"], "Onboarding welcome") {
            snap("01-onboarding", outputDir)
        }
        app.buttons["Skip"].tap()

        // 2. Empty gateway picker
        if waitFor(app.navigationBars["Gateways"], "Gateway picker") {
            snap("02-gateways-empty", outputDir)
        }

        // 3. LAN scan (nothing advertises on the runner; shows the active scan)
        tapButton(containing: "Scan LAN", in: app)
        if waitFor(app.navigationBars["Scan LAN"], "LAN scan") {
            pause(3)
            snap("03-lan-scan", outputDir)
            goBack(app)
        }

        // 4. QR scan (the simulator has no camera: viewfinder over black)
        tapButton(containing: "Open Camera", in: app)
        if waitFor(app.navigationBars["Scan QR Code"], "QR scanner") {
            pause(2)
            snap("04-qr-scan", outputDir)
            goBack(app)
        }

        // 5. Manual Entry, pre-filled by a ghostcrab://pair link
        app.open(pairURL)
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        if springboard.buttons["Open"].waitForExistence(timeout: 3) {
            springboard.buttons["Open"].tap()
        }
        guard waitFor(app.navigationBars["Connect to Gateway"], "Manual Entry from the pairing link") else {
            return
        }
        pause(1)
        snap("05-manual-entry", outputDir)

        // 6. Dashboard (connects to the mock gateway)
        let connect = app.buttons["Connect"]
        guard waitFor(connect, "Connect button") else { return }
        if !connect.isHittable { app.swipeUp() }
        connect.tap()
        guard waitFor(app.navigationBars["Dashboard"], "Dashboard after Connect", timeout: 30) else {
            return
        }
        pause(3)
        snap("06-dashboard", outputDir)

        // 7. Config editor
        if tapButton(containing: "Edit gateway settings", in: app),
           waitFor(app.navigationBars["Configure Gateway"], "Config editor") {
            pause(2)
            snap("07-config-editor", outputDir)
            goBack(app)
        }

        // 8. Models
        if tapButton(containing: "Active model", in: app),
           waitFor(app.navigationBars["Models"], "Model manager") {
            pause(2)
            snap("08-models", outputDir)
            goBack(app)
        }

        // 10. Installed skills
        if tapButton(containing: "Installed skills", in: app) {
            pause(2)
            snap("10-skills", outputDir)
            goBack(app)
        }

        // 11. Settings
        if tapButton(containing: "App preferences", in: app),
           waitFor(app.navigationBars["Settings"], "Settings") {
            pause(1)
            snap("11-settings", outputDir)
            goBack(app)
        }

        // 12. Picker with the saved gateway
        if waitFor(app.navigationBars["Dashboard"], "Dashboard before Disconnect") {
            app.buttons["Disconnect"].firstMatch.tap()
            if waitFor(app.navigationBars["Gateways"], "Gateway picker after Disconnect") {
                pause(1)
                snap("12-gateways-saved", outputDir)
            }
        }
    }

    // MARK: - Helpers

    /// Creates the host directory screenshots are written to, or returns `nil` when unset.
    private func makeOutputDir(_ path: String?) throws -> URL? {
        guard let path, !path.isEmpty else { return nil }
        let url = URL(fileURLWithPath: path, isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    /// Builds a v1 `ghostcrab://pair?p=<base64url JSON>` link.
    private func pairingLink(gatewayURL: String, token: String?, name: String) throws -> URL {
        var payload: [String: Any] = ["v": 1, "url": gatewayURL, "name": name]
        if let token, !token.isEmpty { payload["token"] = token }
        let json = try JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys])
        let encoded = json.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        return try XCTUnwrap(URL(string: "ghostcrab://pair?p=\(encoded)"))
    }

    /// Waits for `element`; records a failure naming `what` when it never appears.
    @MainActor
    @discardableResult
    private func waitFor(_ element: XCUIElement, _ what: String, timeout: TimeInterval = 15) -> Bool {
        let found = element.waitForExistence(timeout: timeout)
        XCTAssertTrue(found, "\(what) did not appear within \(Int(timeout))s")
        return found
    }

    /// Taps the first button whose accessibility label contains `text`. SwiftUI
    /// buttons built from an SF Symbol plus text, or from several texts, expose a
    /// combined label, so exact matching would miss them.
    @MainActor
    @discardableResult
    private func tapButton(containing text: String, in app: XCUIApplication, required: Bool = true) -> Bool {
        let button = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
        let found = button.waitForExistence(timeout: required ? 10 : 3)
        if required {
            XCTAssertTrue(found, "No button containing \"\(text)\"")
        }
        guard found else { return false }
        button.tap()
        return true
    }

    /// Pops the top screen through the navigation bar's back button.
    @MainActor
    private func goBack(_ app: XCUIApplication) {
        let back = app.navigationBars.buttons.element(boundBy: 0)
        if back.waitForExistence(timeout: 5) {
            back.tap()
        } else {
            XCTFail("No back button")
        }
    }

    /// Lets transitions and network-driven content settle before a capture.
    private func pause(_ seconds: TimeInterval) {
        Thread.sleep(forTimeInterval: seconds)
    }

    /// Captures the full screen (status bar included) as a kept attachment and,
    /// when `dir` is set, as `<name>.png` there.
    @MainActor
    private func snap(_ name: String, _ dir: URL?) {
        pause(0.5)
        let shot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: shot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        guard let dir else { return }
        let file = dir.appendingPathComponent("\(name).png")
        do {
            try shot.pngRepresentation.write(to: file)
        } catch {
            XCTFail("Could not write \(file.path): \(error)")
        }
    }
}
