import Foundation
import Testing
@testable import GhostCrab

@Suite("Gateway WebSocket URL")
struct GatewayWebSocketURLTests {

    private func make(_ base: String, allow: Bool = false) throws -> String {
        try GatewayWebSocketURL.make(from: try #require(URL(string: base)), allowCleartextPublicIPs: allow).absoluteString
    }

    @Test func httpBecomesWsAndHttpsBecomesWss() throws {
        #expect(try make("http://192.168.1.50:18789") == "ws://192.168.1.50:18789/ws")
        #expect(try make("https://gw.example:443/") == "wss://gw.example:443/ws")
        #expect(try make("HTTP://gw.local:18789/status?x=1#f") == "ws://gw.local:18789/ws")
    }

    @Test func cleartextToAPublicIPNeedsTheOptIn() async throws {
        guard case .cleartextBlocked(let url, let host)? = await gatewayError({ _ = try make("http://8.8.8.8:18789") }) else {
            Issue.record("expected cleartextBlocked")
            return
        }
        #expect(url == "ws://8.8.8.8:18789/ws")
        #expect(host == "8.8.8.8")
        #expect(try make("http://8.8.8.8:18789", allow: true) == "ws://8.8.8.8:18789/ws")
        #expect(try make("https://8.8.8.8:443") == "wss://8.8.8.8:443/ws")
    }

    @Test func nonHTTPBaseURLsAreRejected() async {
        guard case .api(_, 0, _)? = await gatewayError({ _ = try make("ftp://gw.local:21") }) else {
            Issue.record("expected api(statusCode: 0)")
            return
        }
    }
}
