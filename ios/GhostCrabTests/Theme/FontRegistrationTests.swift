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
