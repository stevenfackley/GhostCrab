import Foundation
import Testing
@testable import GhostCrab

@Suite("Gateway fixtures")
struct GatewayFixturesTests {

    @Test(arguments: GatewayFixtures.all)
    func everyFixtureIsAResOrEventFrame(name: String) throws {
        let object = try #require(try JSONSerialization.jsonObject(with: Data(try GatewayFixtures.text(name).utf8)) as? [String: Any])
        let type = object["type"] as? String
        #expect(type == "res" || type == "event", "\(name) has type \(type ?? "nil")")
    }
}
