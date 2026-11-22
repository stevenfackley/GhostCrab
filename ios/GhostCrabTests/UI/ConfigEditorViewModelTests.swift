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
        guard case .ready(let ready) = vm.state else {
            Issue.record("expected ready, got \(vm.state)")
            return
        }
        #expect(ready.concurrentEditSection == "gateway")
        #expect(ready.config.etag == Self.freshHash)
        #expect(ready.pendingChanges["gateway"]?.objectValue?["port"]?.value as? Int == 18790)
        #expect(ready.pendingChanges["agents"] != nil)
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
        guard case .ready(let ready) = vm.state else {
            Issue.record("expected ready, got \(vm.state)")
            return
        }
        #expect(ready.saveSuccess)
        #expect(ready.concurrentEditSection == nil)
        #expect(ready.pendingChanges["gateway"] == nil)
        #expect(ready.pendingChanges["agents"] != nil) // only the saved section's edit is done
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

        guard case .ready(let ready) = vm.state else {
            Issue.record("expected ready, got \(vm.state)")
            return
        }
        #expect(ready.saveSuccess)
        #expect(repo.updatedSections == ["gateway"])
        #expect(ready.config.sections["gateway"]?.objectValue?["port"]?.value as? Int == 18790)
        #expect(ready.pendingChanges["gateway"] == nil)
        #expect(ready.pendingChanges["agents"]?.objectValue?["note"]?.value as? String == "kept")
        #expect(ready.fieldErrors == ["agents.note": "still being typed"])
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

        guard case .ready(let ready) = vm.state else {
            Issue.record("expected ready, got \(vm.state)")
            return
        }
        #expect(ready.saveSuccess)
        #expect(ready.config.sections["gateway"]?.objectValue?["port"]?.value as? Int == 18790)
        #expect(ready.pendingChanges["gateway"]?.objectValue?["port"]?.value as? Int == 18791)
    }

    @Test func reloadAndDiscardDropsOnlyTheConflictingEditAndWritesNothing() async throws {
        let (vm, socket) = try await conflictedEditor()

        vm.discardConflictingEdit("gateway")

        guard case .ready(let ready) = vm.state else {
            Issue.record("expected ready, got \(vm.state)")
            return
        }
        #expect(ready.concurrentEditSection == nil)
        #expect(ready.config.etag == Self.freshHash) // the conflict already loaded the latest values
        #expect(ready.pendingChanges["gateway"] == nil)
        #expect(ready.pendingChanges["agents"] != nil)
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

        guard case .ready(let ready) = vm.state else {
            Issue.record("expected ready, got \(vm.state)")
            return
        }
        #expect(ready.fieldErrors == ["gateway.port": reason])
        #expect(ready.pendingChanges["gateway"] != nil)
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

        guard case .ready(let ready) = vm.state else {
            Issue.record("expected ready, got \(vm.state)")
            return
        }
        #expect(ready.pendingChanges["gateway"]?.objectValue?["port"]?.value as? Int == 18790)
        #expect(ready.pendingChanges["agents"]?.objectValue?["note"]?.value as? String == "kept")
        #expect(!ready.saveSuccess)
        #expect(ready.pendingSaveSection == nil)
        #expect(ready.concurrentEditSection == nil)
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
        guard case .ready(let ready) = vm.state else {
            Issue.record("expected ready, got \(vm.state)")
            return
        }
        #expect(ready.config.sections["gateway"] != nil)
        #expect(ready.pendingChanges["gateway"]?.objectValue?["port"]?.value as? Int == 18790)
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

    // MARK: - Each mutator changes only its own field of the ready editor

    /// A ready editor over a `gateway` and an `agents` section, loaded from a fake repository.
    private func readyEditor() async -> (ConfigEditorViewModel, FakeConfigRepository) {
        let repo = FakeConfigRepository(config: OpenClawConfig(sections: [
            "gateway": AnyCodable(["port": AnyCodable(18789)]),
            "agents": AnyCodable(["note": AnyCodable("old")]),
        ]))
        let vm = ConfigEditorViewModel(config: repo, gateway: FakeGatewayConnectionManager())
        vm.loadConfig()
        await vm.loadTask?.value
        return (vm, repo)
    }

    private func readyState(_ vm: ConfigEditorViewModel) -> ConfigEditorUiState.Ready? {
        if case .ready(let ready) = vm.state { return ready }
        return nil
    }

    @Test func anEditClearsOnlyThatSectionsFieldErrors() async throws {
        let (vm, _) = await readyEditor()
        vm.setFieldError("gateway.port", error: "Must be 1-65535")
        vm.setFieldError("gatewayish.port", error: "another section")
        vm.setFieldError("agents.note", error: "still being typed")

        vm.editSection("gateway", value: AnyCodable(["port": AnyCodable(18790)]))

        let ready = try #require(readyState(vm))
        #expect(ready.pendingChanges["gateway"]?.objectValue?["port"]?.value as? Int == 18790)
        #expect(ready.fieldErrors == ["gatewayish.port": "another section", "agents.note": "still being typed"])
    }

    @Test func aNilFieldErrorClearsOnlyThatField() async throws {
        let (vm, _) = await readyEditor()
        vm.setFieldError("gateway.port", error: "Must be 1-65535")
        vm.setFieldError("agents.note", error: "still being typed")

        vm.setFieldError("gateway.port", error: nil)

        let ready = try #require(readyState(vm))
        #expect(ready.fieldErrors == ["agents.note": "still being typed"])
    }

    @Test func theDiffSheetOpensOnlyForAnEditedSectionAndCancelKeepsTheEdit() async throws {
        let (vm, repo) = await readyEditor()

        vm.requestSave("gateway")
        let unedited = try #require(readyState(vm))
        #expect(unedited.pendingSaveSection == nil)

        vm.editSection("gateway", value: AnyCodable(["port": AnyCodable(18790)]))
        vm.requestSave("gateway")
        let opened = try #require(readyState(vm))
        #expect(opened.pendingSaveSection == "gateway")

        vm.cancelSave()
        let closed = try #require(readyState(vm))
        #expect(closed.pendingSaveSection == nil)
        #expect(closed.pendingChanges["gateway"]?.objectValue?["port"]?.value as? Int == 18790)
        #expect(repo.updatedSections.isEmpty)
    }

    @Test func discardChangesDropsEveryEditAndFieldErrorAndNothingElse() async throws {
        let (vm, _) = await readyEditor()
        vm.editSection("gateway", value: AnyCodable(["port": AnyCodable(18790)]))
        vm.editSection("agents", value: AnyCodable(["note": AnyCodable("kept")]))
        vm.setFieldError("agents.note", error: "still being typed")
        vm.requestSave("gateway")

        vm.discardChanges()

        let ready = try #require(readyState(vm))
        #expect(ready.pendingChanges.isEmpty)
        #expect(ready.fieldErrors.isEmpty)
        #expect(ready.pendingSaveSection == "gateway")
        #expect(ready.config.sections["agents"]?.objectValue?["note"]?.value as? String == "old")
    }

    @Test func clearingTheSaveBannerKeepsEverythingElse() async throws {
        let (vm, _) = await readyEditor()
        vm.editSection("gateway", value: AnyCodable(["port": AnyCodable(18790)]))
        vm.editSection("agents", value: AnyCodable(["note": AnyCodable("kept")]))
        vm.confirmSave("gateway")
        await vm.saveTask?.value
        let saved = try #require(readyState(vm))
        #expect(saved.saveSuccess)

        vm.clearSaveSuccess()

        let ready = try #require(readyState(vm))
        #expect(!ready.saveSuccess)
        #expect(ready.config.sections["gateway"]?.objectValue?["port"]?.value as? Int == 18790)
        #expect(ready.pendingChanges["agents"]?.objectValue?["note"]?.value as? String == "kept")
    }

    @Test func aReloadOfTheReadyEditorReplacesOnlyTheConfig() async throws {
        let (vm, repo) = await readyEditor()
        vm.editSection("gateway", value: AnyCodable(["port": AnyCodable(18790)]))
        vm.setFieldError("agents.note", error: "still being typed")
        vm.requestSave("gateway")

        vm.loadConfig()
        await vm.loadTask?.value

        #expect(repo.getConfigCount == 2)
        let ready = try #require(readyState(vm))
        #expect(ready.config.sections["agents"] != nil)
        #expect(ready.pendingChanges["gateway"]?.objectValue?["port"]?.value as? Int == 18790)
        #expect(ready.fieldErrors == ["agents.note": "still being typed"])
        #expect(ready.pendingSaveSection == "gateway")
        #expect(vm.saveError == nil)
    }

    @Test func editorMutatorsAreIgnoredUntilTheConfigHasLoaded() {
        let repo = FakeConfigRepository(config: OpenClawConfig(sections: ["gateway": AnyCodable(["port": AnyCodable(18789)])]))
        let vm = ConfigEditorViewModel(config: repo, gateway: FakeGatewayConnectionManager())

        vm.editSection("gateway", value: AnyCodable(["port": AnyCodable(18790)]))
        vm.setFieldError("gateway.port", error: "Must be 1-65535")
        vm.requestSave("gateway")
        vm.confirmSave("gateway")
        vm.discardChanges()
        vm.clearSaveSuccess()
        vm.acknowledgeConflict()

        let stillLoading: Bool
        if case .loading = vm.state { stillLoading = true } else { stillLoading = false }
        #expect(stillLoading)
        #expect(vm.saveTask == nil)
        #expect(repo.updatedSections.isEmpty)
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
