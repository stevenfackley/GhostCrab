import Testing
@testable import GhostCrab

@MainActor
@Suite("InstalledSkillsViewModel")
struct InstalledSkillsViewModelTests {

    @Test func onlyANewSessionRefreshesTheList() async {
        let repo = FakeInstalledSkillRepository(skills: [FakeInstalledSkillRepository.skill("weather")])
        let gateway = ScriptedConnectionManager(ScriptedConnectionManager.connected(generation: 1))
        let vm = InstalledSkillsViewModel(skills: repo, gateway: gateway)
        vm.start()
        await waitUntil { repo.refreshCount == 1 && gateway.subscriberCount == 1 }

        gateway.emit(ScriptedConnectionManager.connected(generation: 1, reconnecting: true))
        gateway.emit(ScriptedConnectionManager.connected(generation: 2))
        await waitUntil { repo.refreshCount >= 2 }
        try? await Task.sleep(for: .milliseconds(50)) // room for a refresh the reconnecting state should not start

        #expect(repo.refreshCount == 2)
        #expect(vm.lastError == nil)
        vm.stop()
        await vm.sessionTask?.value
    }

    @Test func confirmingRemovalDisablesTheSkillInsteadOfUninstalling() async throws {
        let repo = FakeInstalledSkillRepository(skills: [FakeInstalledSkillRepository.skill("weather")])
        let vm = InstalledSkillsViewModel(skills: repo)
        vm.requestUninstall("weather")
        vm.confirmUninstall()
        #expect(vm.pendingUninstallSlug == nil)

        var polls = 0
        while vm.uninstallingSlug != nil, polls < 300 {
            try await Task.sleep(for: .milliseconds(10))
            polls += 1
        }

        #expect(vm.uninstallingSlug == nil)
        #expect(repo.enabledChanges == ["weather=false"])
        #expect(repo.uninstalledSlugs.isEmpty)
        #expect(vm.lastError == nil)
    }

    /// `skills.status` entries without `clawhub.installedVersion` have no version: the row shows only the source,
    /// not a bare `v · Local`.
    @Test(arguments: ["", "  "])
    func aSkillWithoutAVersionHasNoVersionLabel(version: String) {
        #expect(InstalledSkillsViewModel.versionLabel(version) == nil)
    }

    @Test func aSkillWithAVersionIsLabelledWithIt() {
        #expect(InstalledSkillsViewModel.versionLabel("1.4.2") == "v1.4.2")
    }
}
