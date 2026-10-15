import Testing
@testable import GhostCrab

@MainActor
@Suite("ConfigEditorViewModel")
struct ConfigEditorViewModelTests {

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
