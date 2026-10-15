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

    // Transport failures, as Android's OpenClawApiClientErrorMappingTest maps them.

    @Test func aTimeoutMapsToTimeout() async {
        let (client, base) = makeClient { _ in .failing(.timedOut) }
        guard case .timeout(let url, _)? = await gatewayError({ _ = try await client.health() }) else {
            Issue.record("expected GatewayError.timeout")
            return
        }
        #expect(url == base.absoluteString + "/health")
    }

    @Test(arguments: [URLError.Code.serverCertificateUntrusted, .serverCertificateHasBadDate, .secureConnectionFailed].map(\.rawValue))
    func aTLSFailureMapsToTLS(code: Int) async {
        let (client, base) = makeClient { _ in .failing(URLError.Code(rawValue: code)) }
        guard case .tls(let url, _)? = await gatewayError({ _ = try await client.health() }) else {
            Issue.record("expected GatewayError.tls")
            return
        }
        #expect(url == base.absoluteString + "/health")
    }

    @Test(arguments: [URLError.Code.cannotFindHost, .cannotConnectToHost, .notConnectedToInternet].map(\.rawValue))
    func anUnreachableHostMapsToUnreachable(code: Int) async {
        let (client, base) = makeClient { _ in .failing(URLError.Code(rawValue: code)) }
        guard case .unreachable(let url, _)? = await gatewayError({ _ = try await client.health() }) else {
            Issue.record("expected GatewayError.unreachable")
            return
        }
        #expect(url == base.absoluteString + "/health")
    }

    /// Only the caller's own cancellation is a `CancellationError`; a load the system cancelled is a failed request.
    @Test func aCancelledLoadWhileTheCallerRunsMapsToUnreachable() async {
        let (client, _) = makeClient { _ in .failing(.cancelled) }
        guard case .unreachable? = await gatewayError({ _ = try await client.health() }) else {
            Issue.record("expected GatewayError.unreachable")
            return
        }
    }

    @Test func aTrailingSlashOnTheBaseURLDoesNotDoubleTheSlash() async throws {
        let base = MockURLProtocol.uniqueBaseURL()
        MockURLProtocol.register(base) { _ in .init(status: 200, json: #"{"status":"ok"}"#) }
        let slashed = try #require(URL(string: base.absoluteString + "/"))
        let client = OpenClawAPIClient.makeForTesting(baseURL: slashed, protocolClasses: [MockURLProtocol.self])

        #expect(try await client.health().status == "ok")
        #expect(MockURLProtocol.requests(to: base).first?.url?.absoluteString == base.absoluteString + "/health")
    }

    @Test func noCredentialIsEverSent() async throws {
        let (client, base) = makeClient { _ in .init(status: 200, json: #"{"status":"ok"}"#) }
        _ = try await client.health()
        let request = try #require(MockURLProtocol.requests(to: base).first)
        #expect(request.value(forHTTPHeaderField: "Authorization") == nil)
    }
}
