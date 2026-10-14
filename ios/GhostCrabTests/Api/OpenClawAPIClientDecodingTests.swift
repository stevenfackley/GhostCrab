import Testing
import Foundation
@testable import GhostCrab

@Suite("OpenClawAPIClient lenient vs strict decoding")
struct OpenClawAPIClientDecodingTests {

    private func makeClient(_ handler: @escaping MockURLProtocol.Handler) -> (OpenClawAPIClient, URL) {
        let base = MockURLProtocol.uniqueBaseURL()
        MockURLProtocol.register(base, handler: handler)
        return (OpenClawAPIClient.makeForTesting(baseURL: base, protocolClasses: [MockURLProtocol.self]), base)
    }

    /// A JSON-labelled body that does not match the schema must surface as an
    /// API error that names the URL, the real HTTP status and the decoding error.
    private func expectDeserializationFailure(
        url expectedURL: String,
        kind: String,
        _ body: () async throws -> Void
    ) async {
        do {
            try await body()
            Issue.record("expected GatewayError.api for JSON that does not match the schema")
        } catch let error as GatewayError {
            guard case .api(let url, let statusCode, let responseBody) = error else {
                Issue.record("expected GatewayError.api, got \(error)")
                return
            }
            #expect(url == expectedURL)
            #expect(statusCode == 200)
            #expect(responseBody?.hasPrefix("Response deserialization failed (DecodingError.\(kind))") == true)
            let message = error.errorDescription ?? ""
            #expect(message.contains(expectedURL))
            #expect(message.contains("HTTP 200"))
            #expect(message.contains("DecodingError.\(kind)"))
        } catch {
            Issue.record("unexpected error: \(error)")
        }
    }

    @Test func statusHTMLStillYieldsDefaults() async throws {
        let (client, _) = makeClient { _ in .html() }
        #expect(try await client.status() == StatusResponse())
    }

    @Test func statusEmptyJSONObjectYieldsDefaults() async throws {
        let (client, _) = makeClient { _ in .init(status: 200, json: "{}") }
        #expect(try await client.status() == StatusResponse())
    }

    @Test func statusJSONThatIsNotAnObjectIsAnError() async {
        let (client, base) = makeClient { _ in .init(status: 200, json: "[1,2,3]") }
        await expectDeserializationFailure(url: base.absoluteString + "/status", kind: "typeMismatch") {
            _ = try await client.status()
        }
    }

    @Test func modelsHTMLStillYieldsEmptyList() async throws {
        let (client, _) = makeClient { _ in .html() }
        #expect(try await client.getModels().isEmpty)
    }

    @Test func modelsEmptyJSONArrayIsEmptyList() async throws {
        let (client, _) = makeClient { _ in .init(status: 200, json: "[]") }
        #expect(try await client.getModels().isEmpty)
    }

    @Test func modelsJSONWithWrongShapeIsAnError() async {
        let (client, base) = makeClient { _ in .init(status: 200, json: #"{"error":"provider registry offline"}"#) }
        await expectDeserializationFailure(url: base.absoluteString + "/api/models/status", kind: "typeMismatch") {
            _ = try await client.getModels()
        }
    }

    @Test func modelsJSONMissingRequiredFieldIsAnError() async {
        let (client, base) = makeClient { _ in .init(status: 200, json: #"[{"id":"m1"}]"#) }
        await expectDeserializationFailure(url: base.absoluteString + "/api/models/status", kind: "keyNotFound") {
            _ = try await client.getModels()
        }
    }

    @Test func modelsJSONDecodes() async throws {
        let (client, _) = makeClient { _ in
            .init(status: 200, json: #"[{"id":"m1","provider":"openai","isActive":true}]"#)
        }
        let models = try await client.getModels()
        #expect(models.map(\.id) == ["m1"])
        #expect(models.first?.isActive == true)
    }
}
