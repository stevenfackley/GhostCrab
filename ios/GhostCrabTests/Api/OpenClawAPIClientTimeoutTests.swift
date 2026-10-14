import Testing
import Foundation
@testable import GhostCrab

@Suite("OpenClawAPIClient timeouts")
struct OpenClawAPIClientTimeoutTests {

    private func makeClient(_ handler: @escaping MockURLProtocol.Handler) -> (OpenClawAPIClient, URL) {
        let base = MockURLProtocol.uniqueBaseURL()
        MockURLProtocol.register(base, handler: handler)
        return (OpenClawAPIClient.makeForTesting(baseURL: base, protocolClasses: [MockURLProtocol.self]), base)
    }

    @Test func timeoutConstantsHaveTheDocumentedValues() {
        #expect(OpenClawAPIClient.Timeouts.standard == 15)
        #expect(OpenClawAPIClient.Timeouts.longOperation == 120)
        #expect(OpenClawAPIClient.Timeouts.resource == 180)
    }

    @Test func healthUsesStandardTimeout() async throws {
        let (client, base) = makeClient { _ in .init(status: 200, json: #"{"status":"ok"}"#) }
        _ = try await client.health()
        let request = try #require(MockURLProtocol.requests(to: base).first)
        #expect(request.timeoutInterval == OpenClawAPIClient.Timeouts.standard)
    }

    @Test func modelsUseStandardTimeout() async throws {
        let (client, base) = makeClient { _ in .init(status: 200, json: "[]") }
        _ = try await client.getModels()
        let request = try #require(MockURLProtocol.requests(to: base).first)
        #expect(request.timeoutInterval == OpenClawAPIClient.Timeouts.standard)
    }

    @Test func skillInstallUsesLongTimeout() async throws {
        let (client, base) = makeClient { _ in
            .init(status: 200, json: #"{"slug":"wanng-ide/auto-skill-hunter","installed_version":"1.2.0"}"#)
        }
        _ = try await client.installSkill(slug: "wanng-ide/auto-skill-hunter", version: nil)
        let request = try #require(MockURLProtocol.requests(to: base).first)
        #expect(request.timeoutInterval == OpenClawAPIClient.Timeouts.longOperation)
    }

    @Test func aiRecommendUsesLongTimeout() async throws {
        let (client, base) = makeClient { _ in .init(status: 200, json: #"{"recommendation":"use m2"}"#) }
        _ = try await client.getAIRecommendation(AIRecommendationRequestDTO(
            query: "q",
            context: AIContextDTO(activeConfig: nil, hardwareInfo: nil, activeModelId: nil)
        ))
        let request = try #require(MockURLProtocol.requests(to: base).first)
        #expect(request.timeoutInterval == OpenClawAPIClient.Timeouts.longOperation)
    }
}
