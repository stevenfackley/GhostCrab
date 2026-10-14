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
}
