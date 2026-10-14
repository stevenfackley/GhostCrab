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
        try await expectCleartextBlocked(url: "http://8.8.4.4:18789/health", host: "8.8.4.4") {
            _ = try await blocked.health()
        }
    }

    @Test func blockedRequestNeverReachesTheNetwork() async throws {
        let base = try #require(URL(string: "http://9.9.9.9:18789"))
        MockURLProtocol.register(base) { _ in .init(status: 200, json: #"{"status":"ok"}"#) }
        let client = OpenClawAPIClient.makeForTesting(baseURL: base, allowCleartextPublicIPs: false, protocolClasses: [MockURLProtocol.self])

        try await expectCleartextBlocked(url: "http://9.9.9.9:18789/health", host: "9.9.9.9") {
            _ = try await client.health()
        }
        #expect(MockURLProtocol.requests(to: base).isEmpty)
    }

    @Test func blockedErrorNamesTheIOSSettingsToggleAndIsNotRetryable() {
        let error = GatewayError.cleartextBlocked(url: "http://8.8.8.8:18789/health", host: "8.8.8.8")

        #expect(!error.isRetryable)
        #expect(error.errorDescription == "Cleartext HTTP to public IP 8.8.8.8 blocked by app policy. "
            + "Use HTTPS or enable Settings → Security → Allow HTTP to public IPs. "
            + "URL: http://8.8.8.8:18789/health")
    }

    /// A guard refusal must surface as `.cleartextBlocked` (with the fix in
    /// its message), not as a generic `.unreachable`.
    private func expectCleartextBlocked(
        url expectedURL: String,
        host expectedHost: String,
        _ body: () async throws -> Void
    ) async throws {
        do {
            try await body()
            Issue.record("expected GatewayError.cleartextBlocked")
        } catch GatewayError.cleartextBlocked(let url, let host) {
            #expect(url == expectedURL)
            #expect(host == expectedHost)
        }
    }

    @Test func privateIPIsAllowedWithTheFlagOff() async throws {
        let base = try #require(URL(string: "http://192.168.77.20:18789"))
        MockURLProtocol.register(base) { _ in .init(status: 200, json: #"{"status":"ok"}"#) }
        let client = OpenClawAPIClient.makeForTesting(baseURL: base, allowCleartextPublicIPs: false, protocolClasses: [MockURLProtocol.self])

        #expect(try await client.health().status == "ok")
    }

    @Test func untaggedRequestIsNotAllowed() throws {
        let url = try #require(URL(string: "http://1.0.0.1:18789/health"))
        let request = URLRequest(url: url)

        #expect(!CleartextPublicIPGuard.isAllowed(request))
        #expect(CleartextPublicIPGuard.canInit(with: request))
    }

    @Test(arguments: [true, false])
    func taggingCarriesTheClientSetting(allow: Bool) throws {
        let url = try #require(URL(string: "http://1.0.0.1:18789/health"))
        var request = URLRequest(url: url)
        request.timeoutInterval = 15
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        let tagged = CleartextPublicIPGuard.tagged(request, allowCleartextPublicIPs: allow)

        #expect(CleartextPublicIPGuard.isAllowed(tagged) == allow)
        #expect(CleartextPublicIPGuard.canInit(with: tagged) == !allow)
        // Tagging must not drop anything else the request carries.
        #expect(tagged.timeoutInterval == 15)
        #expect(tagged.value(forHTTPHeaderField: "Accept") == "application/json")
    }
}
