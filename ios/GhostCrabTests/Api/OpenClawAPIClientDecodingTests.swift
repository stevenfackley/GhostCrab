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

    // MARK: - Skill install

    /// Pins the convention: REST has no RPC code, so a 200 whose body is not
    /// an installed skill reaches the install stream as
    /// `.protocol(rpcCode: 200, …)`, the HTTP status standing in for the code.
    @Test func skillInstall200WithGarbageBodyIsAProtocolErrorWithCode200() async {
        let (client, _) = makeClient { _ in .init(status: 200, json: "[1,2,3]") }
        do {
            _ = try await client.installSkill(slug: "wanng-ide/auto-skill-hunter", version: nil)
            Issue.record("expected GatewayError.api for a 200 that is not an installed skill")
        } catch let error as GatewayError {
            let mapped = error.toSkillInstallError()
            guard case .protocol(let rpcCode, let message) = mapped else {
                Issue.record("expected SkillInstallError.protocol, got \(mapped)")
                return
            }
            #expect(rpcCode == 200)
            #expect(message.hasPrefix("Response deserialization failed (DecodingError.typeMismatch)"))
            #expect(!mapped.isRetryable)
        } catch {
            Issue.record("unexpected error: \(error)")
        }
    }

    @Test func cleartextBlockedInstallIsNotRetryable() {
        let error = GatewayError.cleartextBlocked(url: "http://8.8.8.8:18789/api/skills/install", host: "8.8.8.8")
        let mapped = error.toSkillInstallError()
        #expect(mapped == .protocol(rpcCode: 0, message: error.errorDescription ?? ""))
        #expect(!mapped.isRetryable)
    }
}
