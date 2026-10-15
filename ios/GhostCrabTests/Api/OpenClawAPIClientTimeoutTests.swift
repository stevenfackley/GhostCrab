import Foundation
import Testing
@testable import GhostCrab

@Suite("OpenClawAPIClient timeouts")
struct OpenClawAPIClientTimeoutTests {

    @Test func timeoutConstantsHaveTheDocumentedValues() {
        #expect(OpenClawAPIClient.Timeouts.standard == 15)
        #expect(OpenClawAPIClient.Timeouts.resource == 180)
    }

    @Test func healthUsesTheStandardTimeout() async throws {
        let base = MockURLProtocol.uniqueBaseURL()
        MockURLProtocol.register(base) { _ in .init(status: 200, json: #"{"status":"ok"}"#) }
        let client = OpenClawAPIClient.makeForTesting(baseURL: base, protocolClasses: [MockURLProtocol.self])
        _ = try await client.health()
        let request = try #require(MockURLProtocol.requests(to: base).first)
        #expect(request.timeoutInterval == OpenClawAPIClient.Timeouts.standard)
    }
}
