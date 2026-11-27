import Testing
@testable import GhostCrab

@MainActor
@Suite("InstalledSkillsViewModel")
struct InstalledSkillsViewModelTests {

    private static let unreachable = GatewayError.unreachable(url: ScriptedConnectionManager.url)

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
        #expect(vm.phase == .ready)
        #expect(vm.skills.map(\.slug) == ["weather"])
        vm.stop()
        await vm.sessionTask?.value
    }

    @Test func aScreenOpenedDuringTheReconnectRefreshesWhenTheNewSessionArrives() async {
        let repo = FakeInstalledSkillRepository(skills: [FakeInstalledSkillRepository.skill("weather")])
        let gateway = ScriptedConnectionManager(ScriptedConnectionManager.connected(generation: 1, reconnecting: true))
        let vm = InstalledSkillsViewModel(skills: repo, gateway: gateway)
        vm.start()
        await waitUntil { gateway.subscriberCount == 1 }
        try? await Task.sleep(for: .milliseconds(50)) // room for a refresh the reconnecting state should not start

        #expect(repo.refreshCount == 0)
        #expect(vm.lastError == nil)
        // The reconnect is in flight: a spinner that the reconnect's end replaces.
        #expect(vm.phase == .loading)

        gateway.emit(ScriptedConnectionManager.connected(generation: 2))
        await waitUntil { repo.refreshCount >= 1 }
        try? await Task.sleep(for: .milliseconds(50))

        #expect(repo.refreshCount == 1)
        #expect(vm.lastError == nil)
        #expect(vm.phase == .ready)
        vm.stop()
        await vm.sessionTask?.value
    }

    // MARK: - Connected: loaded, loading, empty, failed

    @Test func anEmptyListReadOnALiveSessionSaysNoSkillsInstalled() async {
        let repo = FakeInstalledSkillRepository(skills: [])
        let gateway = ScriptedConnectionManager(ScriptedConnectionManager.connected(generation: 1))
        let vm = InstalledSkillsViewModel(skills: repo, gateway: gateway)
        vm.start()
        await waitUntil { vm.phase == .ready }

        #expect(vm.skills.isEmpty)
        #expect(vm.lastError == nil)
        vm.stop()
    }

    @Test func theSpinnerShowsWhileTheFirstReadIsInFlightAndEndsWithItsAnswer() async {
        let repo = FakeInstalledSkillRepository(skills: [])
        repo.holdRefreshes()
        let gateway = ScriptedConnectionManager(ScriptedConnectionManager.connected(generation: 1))
        let vm = InstalledSkillsViewModel(skills: repo, gateway: gateway)
        vm.start()
        await waitUntil { repo.refreshCount == 1 && gateway.subscriberCount == 1 }
        try? await Task.sleep(for: .milliseconds(50)) // room for the not-yet-read empty list to arrive

        // The not-yet-read empty list is not "no skills installed".
        #expect(vm.phase == .loading)
        #expect(vm.isRefreshing)

        repo.releaseRefreshes()
        await waitUntil { vm.phase == .ready }
        #expect(!vm.isRefreshing)
        vm.stop()
    }

    @Test func aFirstReadThatFailsOnALiveSessionIsALoadFailureNotAnEmptyList() async {
        let repo = FakeInstalledSkillRepository(skills: [])
        repo.setRefreshError(Self.unreachable)
        let gateway = ScriptedConnectionManager(ScriptedConnectionManager.connected(generation: 1))
        let vm = InstalledSkillsViewModel(skills: repo, gateway: gateway)
        vm.start()
        await waitUntil { vm.phase != .loading }

        #expect(vm.phase == .loadFailed(message: Self.unreachable.errorDescription ?? ""))
        // Shown in place of the list, not again in an alert.
        #expect(vm.lastError == nil)
        vm.stop()
    }

    @Test func retryAfterAFailedFirstReadLoadsTheList() async {
        let repo = FakeInstalledSkillRepository(skills: [FakeInstalledSkillRepository.skill("weather")])
        repo.setRefreshError(Self.unreachable)
        let vm = InstalledSkillsViewModel(skills: repo)

        await vm.refresh()
        #expect(vm.phase == .loadFailed(message: Self.unreachable.errorDescription ?? ""))

        repo.setRefreshError(nil)
        await vm.refresh()
        #expect(vm.phase == .ready)
        #expect(vm.skills.map(\.slug) == ["weather"])
    }

    @Test func theErrorClearsAfterASuccessfulRefresh() async {
        let repo = FakeInstalledSkillRepository(skills: [FakeInstalledSkillRepository.skill("weather")])
        let vm = InstalledSkillsViewModel(skills: repo)
        await vm.refresh()
        #expect(vm.phase == .ready)

        repo.setRefreshError(Self.unreachable)
        await vm.refresh()
        // The rows stay; the failure is reported on top of them.
        #expect(vm.lastError != nil)
        #expect(vm.phase == .ready)
        #expect(vm.skills.map(\.slug) == ["weather"])

        repo.setRefreshError(nil)
        await vm.refresh()
        #expect(vm.lastError == nil)
    }

    // MARK: - Reconnecting, reconnect failed, never connected

    @Test func aFailedSilentReconnectEndsTheSpinnerWithDisconnectedAndItsReason() async {
        let repo = FakeInstalledSkillRepository(skills: [])
        let gateway = ScriptedConnectionManager(ScriptedConnectionManager.connected(generation: 1, reconnecting: true))
        let vm = InstalledSkillsViewModel(skills: repo, gateway: gateway)
        vm.start()
        await waitUntil { gateway.subscriberCount == 1 }
        #expect(vm.phase == .loading)

        gateway.emit(.error(url: ScriptedConnectionManager.url, cause: Self.unreachable))
        await waitUntil { vm.phase != .loading }

        #expect(vm.phase == .disconnected(reason: Self.unreachable.errorDescription))
        #expect(repo.refreshCount == 0)
        vm.stop()
    }

    @Test func theRowsStayDuringTheSilentReconnectAndGiveWayToDisconnectedWhenItFails() async {
        let repo = FakeInstalledSkillRepository(skills: [FakeInstalledSkillRepository.skill("weather")])
        let gateway = ScriptedConnectionManager(ScriptedConnectionManager.connected(generation: 1))
        let vm = InstalledSkillsViewModel(skills: repo, gateway: gateway)
        vm.start()
        await waitUntil { vm.phase == .ready && !vm.isRefreshing && gateway.subscriberCount == 1 }

        gateway.emit(ScriptedConnectionManager.connected(generation: 1, reconnecting: true))
        try? await Task.sleep(for: .milliseconds(50))
        #expect(vm.phase == .ready)
        #expect(vm.skills.map(\.slug) == ["weather"])

        gateway.emit(.error(url: ScriptedConnectionManager.url, cause: Self.unreachable))
        await waitUntil { vm.phase != .ready }
        #expect(vm.phase == .disconnected(reason: Self.unreachable.errorDescription))
        #expect(vm.skills.isEmpty)
        #expect(repo.refreshCount == 1)
        vm.stop()
    }

    @Test func aReadInFlightWhenTheReconnectFailsDoesNotBringTheListBack() async {
        let repo = FakeInstalledSkillRepository(skills: [])
        repo.holdRefreshes()
        let gateway = ScriptedConnectionManager(ScriptedConnectionManager.connected(generation: 1))
        let vm = InstalledSkillsViewModel(skills: repo, gateway: gateway)
        vm.start()
        await waitUntil { repo.refreshCount == 1 && gateway.subscriberCount == 1 }

        gateway.emit(ScriptedConnectionManager.connected(generation: 1, reconnecting: true))
        gateway.emit(.error(url: ScriptedConnectionManager.url, cause: Self.unreachable))
        await waitUntil { vm.phase != .loading }
        repo.releaseRefreshes()
        try? await Task.sleep(for: .milliseconds(50)) // room for the released read to (wrongly) land

        #expect(vm.phase == .disconnected(reason: Self.unreachable.errorDescription))
        #expect(!vm.isRefreshing)
        vm.stop()
    }

    @Test func aReadThatFailsBecauseTheSocketJustDroppedWaitsForTheReconnectInsteadOfFailing() async {
        let repo = FakeInstalledSkillRepository(skills: [])
        repo.holdRefreshes()
        let gateway = ScriptedConnectionManager(ScriptedConnectionManager.connected(generation: 1))
        let vm = InstalledSkillsViewModel(skills: repo, gateway: gateway)
        vm.start()
        await waitUntil { repo.refreshCount == 1 && gateway.subscriberCount == 1 }

        gateway.emit(ScriptedConnectionManager.connected(generation: 1, reconnecting: true))
        try? await Task.sleep(for: .milliseconds(50)) // the reconnecting state is seen before the read answers
        repo.setRefreshError(GatewayError.connectionClosed(url: ScriptedConnectionManager.url, closeCode: 1006, reason: ""))
        repo.releaseRefreshes()
        await waitUntil { !vm.isRefreshing }

        #expect(vm.phase == .loading)
        vm.stop()
    }

    @Test func neverConnectedShowsDisconnectedWithoutAReasonAndReadsNothing() async {
        let repo = FakeInstalledSkillRepository(skills: [FakeInstalledSkillRepository.skill("weather")])
        let gateway = ScriptedConnectionManager(.disconnected)
        let vm = InstalledSkillsViewModel(skills: repo, gateway: gateway)
        vm.start()

        #expect(vm.phase == .disconnected(reason: nil))
        await waitUntil { gateway.subscriberCount == 1 }
        try? await Task.sleep(for: .milliseconds(50)) // room for a read, or for the replayed list, that must not come
        #expect(vm.phase == .disconnected(reason: nil))
        #expect(vm.skills.isEmpty)
        #expect(repo.refreshCount == 0)
        vm.stop()
    }

    @Test func refreshWhileDisconnectedSendsNothing() async {
        let repo = FakeInstalledSkillRepository(skills: [])
        let gateway = ScriptedConnectionManager(.error(url: ScriptedConnectionManager.url, cause: Self.unreachable))
        let vm = InstalledSkillsViewModel(skills: repo, gateway: gateway)
        vm.start()

        await vm.refresh()

        #expect(vm.phase == .disconnected(reason: Self.unreachable.errorDescription))
        #expect(!vm.isRefreshing)
        #expect(repo.refreshCount == 0)
        vm.stop()
    }

    @Test func aLaterSessionClearsDisconnectedAndLoadsItsList() async {
        let repo = FakeInstalledSkillRepository(skills: [FakeInstalledSkillRepository.skill("weather")])
        let gateway = ScriptedConnectionManager(ScriptedConnectionManager.connected(generation: 1, reconnecting: true))
        let vm = InstalledSkillsViewModel(skills: repo, gateway: gateway)
        vm.start()
        await waitUntil { gateway.subscriberCount == 1 }
        gateway.emit(.error(url: ScriptedConnectionManager.url, cause: Self.unreachable))
        await waitUntil { vm.phase != .loading }

        gateway.emit(ScriptedConnectionManager.connected(generation: 2))
        await waitUntil { vm.phase == .ready }

        #expect(vm.skills.map(\.slug) == ["weather"])
        #expect(repo.refreshCount == 1)
        vm.stop()
    }

    // MARK: - Actions

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
