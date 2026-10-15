import Foundation
import Testing
@testable import GhostCrab

@Suite("ConfigRepositoryImpl (config.*)")
struct ConfigRepositoryImplTests {

    private let fixtureHash = "hmac-sha256:v1:u_qU7JLKF7G9u0jc_GfUp-dKovJYwiLpy0gNxuKjka0"
    private let patchedHash = "hmac-sha256:v1:9zRz_TNpxroYBBCZhMFq3HGpv1dFAcFxPnmwn7VTXYE"

    private func makeRepo(patch: [String]? = nil) async throws -> (ConfigRepositoryImpl, ScriptedGatewayWebSocket) {
        var replies = try [String: [String]].fixtures(["config.get": "res-config-get"])
        replies["config.patch"] = try patch ?? [GatewayFixtures.text("res-config-patch")]
        let socket = try ScriptedGatewayWebSocket(challenge: false, replies: replies)
        let rpc = await startedRPC(socket)
        return (ConfigRepositoryImpl(session: { rpc }), socket)
    }

    private func port(_ value: Int) -> AnyCodable {
        AnyCodable(["port": AnyCodable(value)])
    }

    /// An `INVALID_REQUEST` `config.patch` rejection with `details.issues` (`[path, message]` pairs; nil path omitted).
    private func validationFrame(message: String, issues: [(String?, String)]) throws -> String {
        let list: [[String: Any]] = issues.map { path, text in
            var issue: [String: Any] = ["message": text]
            if let path { issue["path"] = path }
            return issue
        }
        let frame: [String: Any] = [
            "type": "res", "id": "x", "ok": false,
            "error": ["code": "INVALID_REQUEST", "message": message, "details": ["issues": list]],
        ]
        return String(decoding: try JSONSerialization.data(withJSONObject: frame), as: UTF8.self)
    }

    // ── config.get ───────────────────────────────────────────────────────────

    @Test func getConfigMapsParsedSectionsAndTheHash() async throws {
        let (repo, _) = try await makeRepo()
        let config = try await repo.getConfig()
        #expect(config.etag == fixtureHash)
        #expect(config.sections.keys.sorted() == ["agents", "gateway"])
        #expect(config.sections["gateway"]?.objectValue?["port"]?.value as? Int == 18789)
        #expect(config.sections["gateway"]?.object(at: "auth")?["token"]?.value as? String == "__OPENCLAW_REDACTED__")
    }

    @Test func aNonObjectPayloadIsAProtocolViolation() async throws {
        let socket = try ScriptedGatewayWebSocket(
            challenge: false,
            replies: ["config.get": [#"{"type":"res","id":"x","ok":true,"payload":[]}"#]]
        )
        let rpc = await startedRPC(socket)
        guard case .protocolViolation? = await gatewayError({ _ = try await ConfigRepositoryImpl(session: { rpc }).getConfig() }) else {
            Issue.record("expected protocolViolation")
            return
        }
    }

    // ── config.patch ─────────────────────────────────────────────────────────

    @Test func updateConfigPatchesOneSectionAgainstTheCachedHash() async throws {
        let (repo, socket) = try await makeRepo()
        _ = try await repo.getConfig()
        try await repo.updateConfig(section: "gateway", value: port(18790))
        let params = try await sentParams(socket, "config.patch")
        #expect(params["raw"] as? String == #"{"gateway":{"port":18790}}"#)
        #expect(params["baseHash"] as? String == fixtureHash)
        #expect(await socket.sentMethods() == ["config.get", "config.patch"])
    }

    @Test func updateConfigWithoutAPriorReadFetchesTheHashFirst() async throws {
        let (repo, socket) = try await makeRepo()
        try await repo.updateConfig(section: "gateway", value: port(18790))
        #expect(await socket.sentMethods() == ["config.get", "config.patch"])
        #expect(try await sentParams(socket, "config.patch")["baseHash"] as? String == fixtureHash)
    }

    @Test func theHashAPatchReturnsIsTheNextBaseHash() async throws {
        let (repo, socket) = try await makeRepo()
        try await repo.updateConfig(section: "gateway", value: port(18790))
        try await repo.updateConfig(section: "gateway", value: port(18791))
        #expect(await socket.sentMethods() == ["config.get", "config.patch", "config.patch"])
        let second = try jsonObject(try #require(await socket.sentRequests("config.patch").last))
        #expect((second["params"] as? [String: Any])?["baseHash"] as? String == patchedHash)
    }

    @Test func aPatchAnswerWithoutAHashMakesTheNextWriteReRead() async throws {
        let (repo, socket) = try await makeRepo(patch: [okReply])
        try await repo.updateConfig(section: "gateway", value: port(18790))
        try await repo.updateConfig(section: "gateway", value: port(18791))
        #expect(await socket.sentMethods() == ["config.get", "config.patch", "config.get", "config.patch"])
    }

    @Test func aStaleHashIsHTTP412WithTheServerTextSoTheEditorReloads() async throws {
        let (repo, _) = try await makeRepo(patch: [try GatewayFixtures.text("err-config-stale-hash")])
        guard case .api(_, 412, let body)? = await gatewayError({ try await repo.updateConfig(section: "gateway", value: port(1)) }) else {
            Issue.record("expected api 412")
            return
        }
        #expect(body == "config changed since last load; re-run config.get and retry")
    }

    @Test func aMissingHashIsHTTP412Too() async throws {
        let (repo, _) = try await makeRepo(patch: [try GatewayFixtures.text("err-config-base-hash-required")])
        guard case .api(_, 412, _)? = await gatewayError({ try await repo.updateConfig(section: "gateway", value: port(1)) }) else {
            Issue.record("expected api 412")
            return
        }
    }

    @Test func aSchemaIssueInsideTheSectionIsAFieldValidationError() async throws {
        let (repo, _) = try await makeRepo(patch: [try GatewayFixtures.text("err-config-invalid")])
        guard case .configValidation(let field, let reason)? = await gatewayError({
            try await repo.updateConfig(section: "gateway", value: port(99_999))
        }) else {
            Issue.record("expected configValidation")
            return
        }
        #expect(field == "gateway.port")
        #expect(reason == "Too big: expected number to be <=65535 (maximum: 65535)")
    }

    @Test func aSchemaIssueOutsideTheSectionIsHTTP422NamingIt() async throws {
        let frame = try validationFrame(
            message: "invalid config",
            issues: [("agents.defaults.model.primary", "Invalid model ref")]
        )
        let (repo, _) = try await makeRepo(patch: [frame])
        guard case .api(_, 422, let body)? = await gatewayError({ try await repo.updateConfig(section: "gateway", value: port(1)) }) else {
            Issue.record("expected api 422")
            return
        }
        #expect(body == "invalid config; agents.defaults.model.primary: Invalid model ref")
    }

    @Test func severalIssuesAreHTTP422ListingEachOneOnce() async throws {
        let frame = try validationFrame(
            message: "invalid config: gateway.port: Too big",
            issues: [("gateway.port", "Too big"), ("gateway.bind", "Invalid enum value"), (nil, "Unrecognized key")]
        )
        let (repo, _) = try await makeRepo(patch: [frame])
        guard case .api(_, 422, let body)? = await gatewayError({ try await repo.updateConfig(section: "gateway", value: port(1)) }) else {
            Issue.record("expected api 422")
            return
        }
        #expect(body == "invalid config: gateway.port: Too big; gateway.bind: Invalid enum value; Unrecognized key")
    }

    @Test func aRejectionWithoutIssuesIsHTTP422WithTheServerText() async throws {
        let frame = try rejectionFrame(message: "invalid config: JSON5: invalid character '}' at 1:2")
        let (repo, _) = try await makeRepo(patch: [frame])
        guard case .api(_, 422, let body)? = await gatewayError({ try await repo.updateConfig(section: "gateway", value: port(1)) }) else {
            Issue.record("expected api 422")
            return
        }
        #expect(body == "invalid config: JSON5: invalid character '}' at 1:2")
    }

    @Test func aMissingAdminScopePassesThroughTyped() async throws {
        let (repo, _) = try await makeRepo(patch: [try GatewayFixtures.text("err-missing-scope")])
        guard case .missingScope? = await gatewayError({ try await repo.updateConfig(section: "gateway", value: port(1)) }) else {
            Issue.record("expected missingScope")
            return
        }
    }

    @Test func aHashFromAnEarlierSocketIsNeverReused() async throws {
        let first = try ScriptedGatewayWebSocket(challenge: false, replies: try .fixtures(["config.get": "res-config-get"]))
        var secondReplies = try [String: [String]].fixtures(["config.get": "res-config-get"])
        secondReplies["config.patch"] = [okReply]
        let second = try ScriptedGatewayWebSocket(challenge: false, replies: secondReplies)
        let firstRPC = await startedRPC(first)
        let secondRPC = await startedRPC(second)
        let sessions = SessionSequence([firstRPC, secondRPC])
        let repo = ConfigRepositoryImpl(session: { sessions.next() })

        _ = try await repo.getConfig()                                      // first socket
        try await repo.updateConfig(section: "gateway", value: port(18790)) // reconnected: second socket
        #expect(await second.sentMethods() == ["config.get", "config.patch"])
    }

    @Test func disconnectedIsUnreachable() async {
        let repo = ConfigRepositoryImpl(session: { throw GatewayError.unreachable(url: "<no active connection>", underlying: nil) })
        guard case .unreachable? = await gatewayError({ try await repo.updateConfig(section: "gateway", value: port(1)) }) else {
            Issue.record("expected unreachable")
            return
        }
    }
}

/// Hands out sockets in order (the last one repeats), like a reconnect between calls.
private final class SessionSequence: @unchecked Sendable {
    private let lock = NSLock()
    private var sessions: [GatewayRPCClient]

    init(_ sessions: [GatewayRPCClient]) { self.sessions = sessions }

    func next() -> GatewayRPCClient {
        lock.withLock { sessions.count > 1 ? sessions.removeFirst() : sessions[0] }
    }
}
