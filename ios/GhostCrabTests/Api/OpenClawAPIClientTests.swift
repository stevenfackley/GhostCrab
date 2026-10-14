import Testing
import Foundation
@testable import GhostCrab

@Suite("OpenClawAPIClient endpoints and error mapping")
struct OpenClawAPIClientTests {

    private static let aiRequest = AIRecommendationRequestDTO(
        query: "fastest local model?",
        context: AIContextDTO(activeConfig: nil, hardwareInfo: nil, activeModelId: nil)
    )

    private func makeClient(
        token: String? = nil,
        _ handler: @escaping MockURLProtocol.Handler
    ) -> (client: OpenClawAPIClient, base: URL) {
        let base = MockURLProtocol.uniqueBaseURL()
        MockURLProtocol.register(base, handler: handler)
        let client = OpenClawAPIClient.makeForTesting(baseURL: base, token: token, protocolClasses: [MockURLProtocol.self])
        return (client, base)
    }

    @Test func healthDecodesJSON() async throws {
        let (client, base) = makeClient { _ in .init(status: 200, json: #"{"status":"ok"}"#) }
        #expect(try await client.health().status == "ok")
        #expect(MockURLProtocol.requests(to: base).first?.url?.path == "/health")
    }

    @Test func statusToleratesUpstreamHTMLAdminPage() async throws {
        let (client, _) = makeClient { _ in .html() }
        #expect(try await client.status() == StatusResponse())
    }

    @Test func configHTMLMeansNoConfigAPI() async throws {
        let (client, _) = makeClient { _ in .html() }
        let config = try await client.getConfig()
        #expect(config.sections.isEmpty)
        #expect(config.etag == nil)
    }

    @Test(arguments: [401, 403])
    func authStatusesMapToAuthError(code: Int) async {
        let (client, base) = makeClient { _ in .init(status: code, json: "{}") }
        do {
            _ = try await client.status()
            Issue.record("expected GatewayError.auth")
        } catch GatewayError.auth(let url, let statusCode) {
            #expect(statusCode == code)
            #expect(url == base.absoluteString + "/status")
        } catch {
            Issue.record("unexpected error: \(error)")
        }
    }

    @Test func modelsNotFoundMapsToApi404() async {
        let (client, _) = makeClient { _ in .init(status: 404, json: "{}") }
        do {
            _ = try await client.getModels()
            Issue.record("expected GatewayError.api")
        } catch GatewayError.api(_, let statusCode, _) {
            #expect(statusCode == 404)
        } catch {
            Issue.record("unexpected error: \(error)")
        }
    }

    @Test func aiRecommend404MeansSkillNotInstalled() async {
        let (client, base) = makeClient { _ in .init(status: 404, json: "{}") }
        do {
            _ = try await client.getAIRecommendation(Self.aiRequest)
            Issue.record("expected GatewayError.aiServiceUnavailable")
        } catch GatewayError.aiServiceUnavailable(let url) {
            #expect(url == base.absoluteString + "/api/ai/recommend")
        } catch {
            Issue.record("unexpected error: \(error)")
        }
    }

    @Test func aiRecommend429MeansQuotaExceeded() async {
        let (client, _) = makeClient { _ in .init(status: 429, json: "{}") }
        do {
            _ = try await client.getAIRecommendation(Self.aiRequest)
            Issue.record("expected GatewayError.aiQuotaExceeded")
        } catch GatewayError.aiQuotaExceeded {
            // expected
        } catch {
            Issue.record("unexpected error: \(error)")
        }
    }

    @Test func configPatchSendsIfMatchAndMaps412() async throws {
        let (client, base) = makeClient { _ in .init(status: 412, json: "{}") }
        do {
            try await client.updateConfig(section: "gateway", value: AnyCodable(["port": 18790]), etag: "\"v1\"")
            Issue.record("expected GatewayError.api 412")
        } catch GatewayError.api(_, let statusCode, _) {
            #expect(statusCode == 412)
        } catch {
            Issue.record("unexpected error: \(error)")
        }
        let request = try #require(MockURLProtocol.requests(to: base).first)
        #expect(request.httpMethod == "PATCH")
        #expect(request.url?.path == "/config/gateway")
        #expect(request.value(forHTTPHeaderField: "If-Match") == "\"v1\"")
        #expect(request.value(forHTTPHeaderField: "Content-Type") == "application/merge-patch+json")
    }

    @Test func configPatch422WithFieldIsValidationError() async {
        let (client, _) = makeClient { _ in
            .init(status: 422, json: #"{"field":"gateway.http.port","reason":"must be 1-65535"}"#)
        }
        do {
            try await client.updateConfig(section: "gateway", value: AnyCodable(["port": 0]), etag: nil)
            Issue.record("expected GatewayError.configValidation")
        } catch GatewayError.configValidation(let field, let reason) {
            #expect(field == "gateway.http.port")
            #expect(reason == "must be 1-65535")
        } catch {
            Issue.record("unexpected error: \(error)")
        }
    }

    @Test func configPatch422WithoutFieldKeepsTheBody() async {
        let (client, _) = makeClient { _ in .init(status: 422, json: #"{"error":"schema"}"#) }
        do {
            try await client.updateConfig(section: "gateway", value: AnyCodable(["port": 0]), etag: nil)
            Issue.record("expected GatewayError.api 422")
        } catch GatewayError.api(_, let statusCode, let body) {
            #expect(statusCode == 422)
            #expect(body == #"{"error":"schema"}"#)
        } catch {
            Issue.record("unexpected error: \(error)")
        }
    }

    @Test func authenticatedClientSendsBearerToken() async throws {
        let (client, base) = makeClient(token: "tok_secret") { _ in .init(status: 200, json: #"{"status":"ok"}"#) }
        _ = try await client.health()
        let request = try #require(MockURLProtocol.requests(to: base).first)
        #expect(request.value(forHTTPHeaderField: "Authorization") == "Bearer tok_secret")
    }

    @Test func unauthenticatedClientSendsNoAuthorization() async throws {
        let (client, base) = makeClient { _ in .init(status: 200, json: #"{"status":"ok"}"#) }
        _ = try await client.health()
        let request = try #require(MockURLProtocol.requests(to: base).first)
        #expect(request.value(forHTTPHeaderField: "Authorization") == nil)
    }

    @Test func setActiveModelPostsToActiveEndpoint() async throws {
        let (client, base) = makeClient { _ in .init(status: 204) }
        try await client.setActiveModel("m2")
        let request = try #require(MockURLProtocol.requests(to: base).first)
        #expect(request.httpMethod == "POST")
        #expect(request.url?.path == "/api/models/active")
    }
}
