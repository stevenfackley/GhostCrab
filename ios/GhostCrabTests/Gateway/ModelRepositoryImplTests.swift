import Foundation
import Testing
@testable import GhostCrab

@Suite("ModelRepositoryImpl (models.list)")
struct ModelRepositoryImplTests {

    private let catalogue = #"{"type":"res","id":"x","ok":true,"payload":{"models":[{"id":"claude-sonnet-4-6","name":"Claude Sonnet 4.6","provider":"anthropic","available":true,"input":["text","image"],"reasoning":true},{"id":"gpt-6-astra","name":"GPT-6 Astra","provider":"openai","available":false,"unavailableReason":"missing-auth","input":["text"]}]}}"#

    /// Two providers list `shared`; only one lists `solo`.
    private let sharedCatalogue = #"{"type":"res","id":"x","ok":true,"payload":{"models":[{"id":"shared","provider":"anthropic"},{"id":"shared","provider":"openrouter"},{"id":"solo","provider":"openai"}]}}"#

    private func configWithPrimary(_ primary: String) -> String {
        #"{"type":"res","id":"x","ok":true,"payload":{"parsed":{"agents":{"defaults":{"model":{"primary":""#
            + primary + #""}}}},"hash":"h-1"}}"#
    }

    private func makeRepo(_ replies: [String: [String]]) async throws -> (ModelRepositoryImpl, ScriptedGatewayWebSocket) {
        let socket = try ScriptedGatewayWebSocket(challenge: false, replies: replies)
        let rpc = await startedRPC(socket)
        return (ModelRepositoryImpl(session: { rpc }), socket)
    }

    // ── getModels ────────────────────────────────────────────────────────────

    @Test func modelsMapToProviderQualifiedRefsAndTheConfiguredPrimaryIsActive() async throws {
        let (repo, _) = try await makeRepo(["models.list": [catalogue], "config.get": [configWithPrimary("anthropic/claude-sonnet-4-6")]])
        let list = try await repo.getModels()
        #expect(list.map(\.id) == ["anthropic/claude-sonnet-4-6", "openai/gpt-6-astra"])
        #expect(list[0].isActive)
        #expect(list[0].provider == "anthropic")
        #expect(list[0].displayName == "Claude Sonnet 4.6")
        #expect(list[0].status == "ready")
        #expect(list[0].capabilities == ["text", "image", "reasoning"])
        #expect(!list[1].isActive)
        #expect(list[1].status == "missing-auth")
    }

    @Test func theStringFormOfAgentsDefaultsModelAlsoMarksTheActiveEntry() async throws {
        let stringForm = #"{"type":"res","id":"x","ok":true,"payload":{"parsed":{"agents":{"defaults":{"model":"openai/gpt-6-astra"}}}}}"#
        let (repo, _) = try await makeRepo(["models.list": [catalogue], "config.get": [stringForm]])
        #expect(try await repo.getModels().map(\.isActive) == [false, true])
    }

    @Test func aBareIdMarksTheEntryActiveWhenOnlyOneProviderListsIt() async throws {
        let (repo, _) = try await makeRepo(["models.list": [sharedCatalogue], "config.get": [configWithPrimary("solo")]])
        let list = try await repo.getModels()
        #expect(list.map(\.id) == ["anthropic/shared", "openrouter/shared", "openai/solo"])
        #expect(list.map(\.isActive) == [false, false, true])
    }

    @Test func aProviderQualifiedPrimaryPicksThatProvidersEntry() async throws {
        let (repo, _) = try await makeRepo(["models.list": [sharedCatalogue], "config.get": [configWithPrimary("openrouter/shared")]])
        #expect(try await repo.getModels().map(\.isActive) == [false, true, false])
    }

    @Test func anAmbiguousBareIdNeverMarksTwoProvidersAndShowsTheRawPrimary() async throws {
        let (repo, _) = try await makeRepo(["models.list": [sharedCatalogue], "config.get": [configWithPrimary("shared")]])
        let list = try await repo.getModels()
        #expect(list.count == 4)
        #expect(list.prefix(3).allSatisfy { !$0.isActive })
        let raw = try #require(list.last)
        #expect(raw.id == "shared")
        #expect(raw.displayName == "shared")
        #expect(raw.provider == "")
        #expect(raw.isActive)
        #expect(raw.status == "ambiguous")
        #expect(list.filter(\.isActive).count == 1)
    }

    @Test func aPrimaryTheCatalogueLacksIsShownRawAndActive() async throws {
        let (repo, _) = try await makeRepo(["models.list": [catalogue], "config.get": [configWithPrimary("anthropic/claude-opus-9")]])
        let list = try await repo.getModels()
        #expect(list.map(\.id) == ["anthropic/claude-sonnet-4-6", "openai/gpt-6-astra", "anthropic/claude-opus-9"])
        #expect(list.map(\.isActive) == [false, false, true])
        #expect(list[2].provider == "anthropic")
        #expect(list[2].status == "not-listed")
    }

    @Test func aModelWithoutANameUsesItsIdAsTheDisplayName() async throws {
        let unnamed = #"{"type":"res","id":"x","ok":true,"payload":{"models":[{"id":"llama3","provider":"ollama"}]}}"#
        let (repo, _) = try await makeRepo(["models.list": [unnamed], "config.get": [try GatewayFixtures.text("res-config-get")]])
        let model = try #require(try await repo.getModels().first)
        #expect(model.id == "ollama/llama3")
        #expect(model.displayName == "llama3")
        #expect(model.status == "ready")
        #expect(!model.isActive)
    }

    @Test func nothingIsActiveWhileNoPrimaryModelIsConfigured() async throws {
        let (repo, _) = try await makeRepo(["models.list": [catalogue], "config.get": [try GatewayFixtures.text("res-config-get")]])
        let list = try await repo.getModels()
        #expect(list.count == 2)
        #expect(list.allSatisfy { !$0.isActive })
    }

    @Test func theRealEmptyCatalogueMapsToAnEmptyList() async throws {
        let (repo, _) = try await makeRepo(try .fixtures(["models.list": "res-models-list", "config.get": "res-config-get"]))
        #expect(try await repo.getModels().isEmpty)
    }

    @Test func withoutOperatorAdminTheListStillLoadsWithNothingActive() async throws {
        let (repo, _) = try await makeRepo(["models.list": [catalogue], "config.get": [try GatewayFixtures.text("err-missing-scope")]])
        let list = try await repo.getModels()
        #expect(list.count == 2)
        #expect(list.allSatisfy { !$0.isActive })
    }

    @Test func aRefusedModelsListPassesThroughTyped() async throws {
        let (repo, _) = try await makeRepo(try .fixtures(["models.list": "err-missing-scope"]))
        guard case .missingScope? = await gatewayError({ _ = try await repo.getModels() }) else {
            Issue.record("expected missingScope")
            return
        }
    }

    @Test func aGatewayWithoutModelsListIsMethodUnsupported() async throws {
        let (repo, _) = try await makeRepo(["models.list": [try rejectionFrame(message: "unknown method: models.list")]])
        guard case .methodUnsupported(_, "models.list")? = await gatewayError({ _ = try await repo.getModels() }) else {
            Issue.record("expected methodUnsupported")
            return
        }
    }

    // ── setActiveModel ───────────────────────────────────────────────────────

    @Test func setActiveModelPatchesThePrimaryAgainstTheCurrentHash() async throws {
        let (repo, socket) = try await makeRepo(["config.get": [configWithPrimary("anthropic/claude-sonnet-4-6")], "config.patch": [okReply]])
        try await repo.setActiveModel(modelId: "openai/gpt-6-astra")
        let params = try await sentParams(socket, "config.patch")
        #expect(params["raw"] as? String == #"{"agents":{"defaults":{"model":{"primary":"openai/gpt-6-astra"}}}}"#)
        #expect(params["baseHash"] as? String == "h-1")
        #expect(await socket.sentMethods() == ["config.get", "config.patch"])
    }

    @Test func aRejectedModelIsHTTP422() async throws {
        let rejection = try rejectionFrame(message: "invalid config: unknown model x/y")
        let (repo, _) = try await makeRepo(["config.get": [configWithPrimary("a/b")], "config.patch": [rejection]])
        guard case .api(_, 422, _)? = await gatewayError({ try await repo.setActiveModel(modelId: "x/y") }) else {
            Issue.record("expected api 422")
            return
        }
    }

    @Test func aSchemaIssueOnThePrimaryIsHTTP422NeverAFieldError() async throws {
        let rejection = #"{"type":"res","id":"x","ok":false,"error":{"code":"INVALID_REQUEST","message":"invalid config: agents.defaults.model.primary: Invalid model ref","details":{"issues":[{"path":"agents.defaults.model.primary","message":"Invalid model ref"}]}}}"#
        let (repo, _) = try await makeRepo(["config.get": [configWithPrimary("a/b")], "config.patch": [rejection]])
        guard case .api(_, 422, let body)? = await gatewayError({ try await repo.setActiveModel(modelId: "x") }) else {
            Issue.record("expected api 422")
            return
        }
        #expect(body == "invalid config: agents.defaults.model.primary: Invalid model ref")
    }

    @Test func aConcurrentConfigChangeIsTheSameHTTP412TheEditorReloadsOn() async throws {
        let (repo, _) = try await makeRepo([
            "config.get": [configWithPrimary("a/b")],
            "config.patch": [try GatewayFixtures.text("err-config-stale-hash")],
        ])
        guard case .api(_, 412, let body)? = await gatewayError({ try await repo.setActiveModel(modelId: "x/y") }) else {
            Issue.record("expected api 412")
            return
        }
        #expect(body == "config changed since last load; re-run config.get and retry")
    }

    @Test func refsAreProviderQualifiedExactlyOnce() {
        #expect(modelRef(provider: "anthropic", id: "claude") == "anthropic/claude")
        #expect(modelRef(provider: "openrouter", id: "openrouter/x") == "openrouter/x")
    }
}
