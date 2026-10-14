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
