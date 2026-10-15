import Foundation
import Testing
@testable import GhostCrab

@MainActor
@Suite("ConfigEditorViewModel")
struct ConfigEditorViewModelTests {

    private static let freshHash = "hmac-sha256:v1:after-someone-elses-write"

    /// `res-config-get` with its `hash` replaced: the config as someone else's write left it.
    private func configGetFrame(hash: String) throws -> String {
        var frame = try jsonObject(try GatewayFixtures.text("res-config-get"))
        var payload = try #require(frame["payload"] as? [String: Any])
        payload["hash"] = hash
        frame["payload"] = payload
        return String(decoding: try JSONSerialization.data(withJSONObject: frame), as: UTF8.self)
    }

    /// An editor over the real repository whose `gateway` save just hit a stale `baseHash` (someone else wrote
    /// first): `config.get` answers the loaded hash, then ``freshHash``; the first `config.patch` is rejected as
    /// stale, every later one succeeds. Pending edits: `gateway.port` 18790 and an `agents` edit.
    private func conflictedEditor() async throws -> (ConfigEditorViewModel, ScriptedGatewayWebSocket) {
        let replies: [String: [String]] = [
            "config.get": [try GatewayFixtures.text("res-config-get"), try configGetFrame(hash: Self.freshHash)],
            "config.patch": [try GatewayFixtures.text("err-config-stale-hash"), try GatewayFixtures.text("res-config-patch")],
        ]
        let socket = try ScriptedGatewayWebSocket(challenge: false, replies: replies)
        let rpc = await startedRPC(socket)
        let vm = ConfigEditorViewModel(config: ConfigRepositoryImpl(session: { rpc }), gateway: FakeGatewayConnectionManager())
        vm.loadConfig()
        await vm.loadTask?.value
        vm.editSection("gateway", value: AnyCodable(["port": AnyCodable(18790)]))
        vm.editSection("agents", value: AnyCodable(["note": AnyCodable("kept")]))
        vm.confirmSave("gateway")
        await vm.saveTask?.value
        return (vm, socket)
    }

    @Test func aConflictReloadsTheConfigAndKeepsThePendingEdits() async throws {
        let (vm, _) = try await conflictedEditor()
        guard case .ready(let config, let pending, _, _, _, let conflict) = vm.state else {
            Issue.record("expected ready, got \(vm.state)")
            return
        }
        #expect(conflict == "gateway")
        #expect(config.etag == Self.freshHash)
        #expect(pending["gateway"]?.objectValue?["port"]?.value as? Int == 18790)
        #expect(pending["agents"] != nil)
    }

    @Test func forceOverwriteResendsThePendingEditAgainstTheFreshHash() async throws {
        let (vm, socket) = try await conflictedEditor()

        vm.forceOverwrite("gateway")
        await vm.saveTask?.value

        let patches = await socket.sentRequests("config.patch")
        #expect(patches.count == 2)
        let retry = try jsonObject(try #require(patches.last))
        #expect((retry["params"] as? [String: Any])?["baseHash"] as? String == Self.freshHash)
        #expect((retry["params"] as? [String: Any])?["raw"] as? String == #"{"gateway":{"port":18790}}"#)
        guard case .ready(_, let pending, _, _, let saveSuccess, let conflict) = vm.state else {
            Issue.record("expected ready, got \(vm.state)")
            return
        }
        #expect(saveSuccess)
        #expect(conflict == nil)
        #expect(pending["gateway"] == nil)
        #expect(pending["agents"] != nil) // only the saved section's edit is done
    }

    @Test func aSaveKeepsTheOtherSectionsPendingEdits() async throws {
        let repo = FakeConfigRepository(config: OpenClawConfig(sections: [
            "gateway": AnyCodable(["port": AnyCodable(18789)]),
            "agents": AnyCodable(["note": AnyCodable("old")]),
        ]))
        let vm = ConfigEditorViewModel(config: repo, gateway: FakeGatewayConnectionManager())
        vm.loadConfig()
        await vm.loadTask?.value
        vm.editSection("gateway", value: AnyCodable(["port": AnyCodable(18790)]))
        vm.editSection("agents", value: AnyCodable(["note": AnyCodable("kept")]))
        vm.setFieldError("agents.note", error: "still being typed")

        vm.confirmSave("gateway")
        await vm.saveTask?.value

        guard case .ready(let config, let pending, let fieldErrors, _, let saveSuccess, _) = vm.state else {
            Issue.record("expected ready, got \(vm.state)")
            return
        }
        #expect(saveSuccess)
        #expect(repo.updatedSections == ["gateway"])
        #expect(config.sections["gateway"]?.objectValue?["port"]?.value as? Int == 18790)
        #expect(pending["gateway"] == nil)
        #expect(pending["agents"]?.objectValue?["note"]?.value as? String == "kept")
        #expect(fieldErrors == ["agents.note": "still being typed"])
    }

    @Test func aReEditMadeDuringTheSaveSurvivesIt() async throws {
        let repo = FakeConfigRepository(config: OpenClawConfig(sections: ["gateway": AnyCodable(["port": AnyCodable(18789)])]))
        let vm = ConfigEditorViewModel(config: repo, gateway: FakeGatewayConnectionManager())
        vm.loadConfig()
        await vm.loadTask?.value
        vm.editSection("gateway", value: AnyCodable(["port": AnyCodable(18790)]))

        vm.confirmSave("gateway")
        // The save task has not run yet (both are on the main actor): this edit lands while the save is in flight.
        vm.editSection("gateway", value: AnyCodable(["port": AnyCodable(18791)]))
        await vm.saveTask?.value

        guard case .ready(let config, let pending, _, _, let saveSuccess, _) = vm.state else {
            Issue.record("expected ready, got \(vm.state)")
            return
        }
        #expect(saveSuccess)
        #expect(config.sections["gateway"]?.objectValue?["port"]?.value as? Int == 18790)
        #expect(pending["gateway"]?.objectValue?["port"]?.value as? Int == 18791)
    }

    @Test func reloadAndDiscardDropsOnlyTheConflictingEditAndWritesNothing() async throws {
        let (vm, socket) = try await conflictedEditor()

        vm.discardConflictingEdit("gateway")

        guard case .ready(let config, let pending, _, _, _, let conflict) = vm.state else {
            Issue.record("expected ready, got \(vm.state)")
            return
        }
        #expect(conflict == nil)
        #expect(config.etag == Self.freshHash) // the conflict already loaded the latest values
        #expect(pending["gateway"] == nil)
        #expect(pending["agents"] != nil)
        #expect(await socket.sentRequests("config.patch").count == 1)
        #expect(vm.saveTask == nil)
    }

    @Test func secondConfirmWhileSavingIsIgnored() async {
        let repo = FakeConfigRepository(config: OpenClawConfig(sections: ["gateway": AnyCodable(["port": 18789])]))
        let vm = ConfigEditorViewModel(config: repo, gateway: FakeGatewayConnectionManager())
        vm.loadConfig()
        await vm.loadTask?.value
        vm.editSection("gateway", value: AnyCodable(["port": 18790]))

        vm.confirmSave("gateway")
        vm.confirmSave("gateway")
        await vm.saveTask?.value

        #expect(repo.updatedSections == ["gateway"])
        #expect(vm.saveTask == nil)
    }

    @Test func aServerValidationErrorLandsOnItsFieldAndKeepsTheEdit() async {
        let reason = "Too big: expected number to be <=65535 (maximum: 65535)"
        let repo = RejectingConfigRepository(
            config: OpenClawConfig(sections: ["gateway": AnyCodable(["port": 18789])]),
            error: .configValidation(field: "gateway.port", reason: reason)
        )
        let vm = ConfigEditorViewModel(config: repo, gateway: FakeGatewayConnectionManager())
        vm.loadConfig()
        await vm.loadTask?.value
        vm.editSection("gateway", value: AnyCodable(["port": 99_999]))

        vm.confirmSave("gateway")
        await vm.saveTask?.value

        guard case .ready(_, let pending, let fieldErrors, _, _, _) = vm.state else {
            Issue.record("expected ready, got \(vm.state)")
            return
        }
        #expect(fieldErrors == ["gateway.port": reason])
        #expect(pending["gateway"] != nil)
    }

    @Test func repeatedLoadWhileLoadingIsIgnored() async {
        let repo = FakeConfigRepository(config: OpenClawConfig(sections: ["gateway": AnyCodable(["port": 18789])]))
        let vm = ConfigEditorViewModel(config: repo, gateway: FakeGatewayConnectionManager())

        vm.loadConfig()
        let first = vm.loadTask
        vm.loadConfig()

        #expect(first != nil)
        #expect(vm.loadTask == first)
        await vm.loadTask?.value
        #expect(vm.loadTask == nil)
    }

    @Test func loadAfterAFinishedLoadRunsAgain() async {
        let repo = FakeConfigRepository(config: OpenClawConfig(sections: [:]))
        let vm = ConfigEditorViewModel(config: repo, gateway: FakeGatewayConnectionManager())

        vm.loadConfig()
        await vm.loadTask?.value
        vm.loadConfig()

        #expect(vm.loadTask != nil)
        await vm.loadTask?.value
        let showsNoConfigApi: Bool
        if case .noConfigApi = vm.state { showsNoConfigApi = true } else { showsNoConfigApi = false }
        #expect(showsNoConfigApi)
    }

    @Test func aSaveThatFailsBecauseTheSessionDroppedKeepsEveryPendingEdit() async {
        let endpoint = "ws://192.168.1.20:18789/ws"
        let repo = RejectingConfigRepository(
            config: OpenClawConfig(sections: [
                "gateway": AnyCodable(["port": AnyCodable(18789)]),
                "agents": AnyCodable(["note": AnyCodable("old")]),
            ]),
            error: .unreachable(url: endpoint)
        )
        let vm = ConfigEditorViewModel(config: repo, gateway: FakeGatewayConnectionManager())
        vm.loadConfig()
        await vm.loadTask?.value
        vm.editSection("gateway", value: AnyCodable(["port": AnyCodable(18790)]))
        vm.editSection("agents", value: AnyCodable(["note": AnyCodable("kept")]))

        vm.confirmSave("gateway")
        await vm.saveTask?.value

        guard case .ready(_, let pending, _, let saveSection, let saveSuccess, let conflict) = vm.state else {
            Issue.record("expected ready, got \(vm.state)")
            return
        }
        #expect(pending["gateway"]?.objectValue?["port"]?.value as? Int == 18790)
        #expect(pending["agents"]?.objectValue?["note"]?.value as? String == "kept")
        #expect(!saveSuccess)
        #expect(saveSection == nil)
        #expect(conflict == nil)
        #expect(vm.saveError?.hasPrefix("Save failed: ") == true)
        #expect(vm.saveError?.contains(endpoint) == true)

        vm.clearSaveError()
        #expect(vm.saveError == nil)
    }

    @Test func aReconnectKeepsTheEditsAndTheNewSessionReloadsOnlyTheConfig() async {
        let repo = FakeConfigRepository(config: OpenClawConfig(sections: ["gateway": AnyCodable(["port": AnyCodable(18789)])]))
        let gateway = ScriptedConnectionManager(ScriptedConnectionManager.connected(generation: 1))
        let vm = ConfigEditorViewModel(config: repo, gateway: gateway)
        vm.start()
        await waitUntil { repo.getConfigCount == 1 && gateway.subscriberCount == 1 }
        await vm.loadTask?.value
        vm.editSection("gateway", value: AnyCodable(["port": AnyCodable(18790)]))

        gateway.emit(ScriptedConnectionManager.connected(generation: 1, reconnecting: true))
        gateway.emit(ScriptedConnectionManager.connected(generation: 2))
        await waitUntil { repo.getConfigCount >= 2 }
        await vm.loadTask?.value

        #expect(repo.getConfigCount == 2) // the reconnecting state loaded nothing
        guard case .ready(let config, let pending, _, _, _, _) = vm.state else {
            Issue.record("expected ready, got \(vm.state)")
            return
        }
        #expect(config.sections["gateway"] != nil)
        #expect(pending["gateway"]?.objectValue?["port"]?.value as? Int == 18790)
        #expect(vm.saveError == nil)
        vm.stop()
    }

    @Test func cancelledLoadPublishesNoError() async {
        let vm = ConfigEditorViewModel(config: HangingConfigRepository(), gateway: FakeGatewayConnectionManager())

        vm.loadConfig()
        let load = vm.loadTask
        // The screen went away mid-load.
        vm.stop()
        await load?.value

        let showsError: Bool
        if case .error = vm.state { showsError = true } else { showsError = false }
        #expect(!showsError)
    }
}

/// `getConfig()` answers `config`; every `updateConfig` throws `error`.
private struct RejectingConfigRepository: ConfigRepository {
    let config: OpenClawConfig
    let error: GatewayError

    func getConfig() async throws -> OpenClawConfig { config }

    func updateConfig(section: String, value: AnyCodable) async throws { throw error }
}

/// `getConfig()` waits until its task is cancelled, then fails the way a
/// cancelled request reached the VM before: as an unreachable gateway.
private struct HangingConfigRepository: ConfigRepository {
    func getConfig() async throws -> OpenClawConfig {
        do {
            try await Task.sleep(for: .seconds(60))
        } catch {
            throw GatewayError.unreachable(url: "http://192.168.1.20:18789/config")
        }
        return OpenClawConfig(sections: [:])
    }

    func updateConfig(section: String, value: AnyCodable) async throws {}
}
