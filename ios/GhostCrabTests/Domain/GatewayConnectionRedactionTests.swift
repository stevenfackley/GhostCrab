import Testing
import Foundation
@testable import GhostCrab

@Suite("GatewayConnection redaction")
struct GatewayConnectionRedactionTests {

    private static func connected(token: String?) -> GatewayConnection {
        .connected(
            url: "http://192.168.1.20:18789",
            displayName: "Basement",
            version: "1.0.0",
            authRequirement: token == nil ? AuthRequirement.none : AuthRequirement.token,
            isHttps: false,
            capabilities: ["skill-ai-recommend"],
            tokenOrNull: token
        )
    }

    @Test func descriptionsNeverContainTheToken() {
        let state = Self.connected(token: "tok_secret_123")

        #expect(!String(describing: state).contains("tok_secret_123"))
        #expect(!String(reflecting: state).contains("tok_secret_123"))
        #expect(!"\(state)".contains("tok_secret_123"))
        #expect(String(describing: state).contains("tokenOrNull: <redacted>"))
        #expect(String(describing: state).contains("http://192.168.1.20:18789"))
    }

    @Test func dumpNeverContainsTheToken() {
        var dumped = ""
        dump(Self.connected(token: "tok_secret_123"), to: &dumped)

        #expect(!dumped.contains("tok_secret_123"))
        #expect(dumped.contains("<redacted>"))
    }

    @Test func aTokenlessSessionSaysNil() {
        #expect(String(describing: Self.connected(token: nil)).contains("tokenOrNull: nil"))
    }
}
