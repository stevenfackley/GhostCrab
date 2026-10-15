import Foundation
import Testing
@testable import GhostCrab

@Suite("OpenClawAPIClient (/health)")
struct OpenClawAPIClientTests {

    private func makeClient(_ handler: @escaping MockURLProtocol.Handler) -> (client: OpenClawAPIClient, base: URL) {
        let base = MockURLProtocol.uniqueBaseURL()
        MockURLProtocol.register(base, handler: handler)
        return (OpenClawAPIClient.makeForTesting(baseURL: base, protocolClasses: [MockURLProtocol.self]), base)
    }

    @Test func healthDecodesJSON() async throws {
        let (client, base) = makeClient { _ in .init(status: 200, json: #"{"status":"ok"}"#) }
        #expect(try await client.health().status == "ok")
        #expect(MockURLProtocol.requests(to: base).first?.url?.path == "/health")
    }

    @Test func healthToleratesANonJSONBody() async throws {
        let (client, _) = makeClient { _ in .html() }
        #expect(try await client.health().status == "ok")
    }

    @Test(arguments: [401, 403])
    func authStatusesMapToAuthError(code: Int) async {
        let (client, base) = makeClient { _ in .init(status: code, json: "{}") }
        guard case .auth(let url, code)? = await gatewayError({ _ = try await client.health() }) else {
            Issue.record("expected GatewayError.auth")
            return
        }
        #expect(url == base.absoluteString + "/health")
    }

    @Test func serverErrorsMapToApi() async {
        let (client, _) = makeClient { _ in .init(status: 503, json: "{}") }
        guard case .api(_, 503, _)? = await gatewayError({ _ = try await client.health() }) else {
            Issue.record("expected GatewayError.api 503")
            return
        }
    }

    @Test func noCredentialIsEverSent() async throws {
        let (client, base) = makeClient { _ in .init(status: 200, json: #"{"status":"ok"}"#) }
        _ = try await client.health()
        let request = try #require(MockURLProtocol.requests(to: base).first)
        #expect(request.value(forHTTPHeaderField: "Authorization") == nil)
    }
}
