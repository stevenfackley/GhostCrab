import Testing
@testable import GhostCrab

@MainActor
@Suite("InstalledSkillsViewModel")
struct InstalledSkillsViewModelTests {

    /// What the socket's end fails pending requests with (and what a lookup without a live session throws).
    private static let unreachable = GatewayError.unreachable(url: ScriptedConnectionManager.url)
    /// A failure from a live socket: this request's own timeout.
    private static let timedOut = GatewayError.timeout(url: ScriptedConnectionManager.url + "#skills.status")
    /// Long enough that it never runs out in a test that ends the wait with a state change.
    private static let longGrace: Duration = .seconds(30)

    private static func weather() -> InstalledSkill { FakeInstalledSkillRepository.skill("weather") }

    nonisolated private static func isLoadFailed(_ phase: InstalledSkillsPhase) -> Bool {
        if case .loadFailed = phase { return true }
        return false
    }

    /// Neither "Couldn't load skills" nor an alert was ever set (every phase and message is recorded, not only the
    /// last one).
    private static func expectNoFailureShown(_ vm: InstalledSkillsViewModel) {
        #expect(!vm.phaseLog.contains(where: isLoadFailed), "phases: \(vm.phaseLog)")
        #expect(vm.errorLog.isEmpty, "alerts: \(vm.errorLog)")
    }

    @Test func onlyANewSessionRefreshesTheList() async {
        let repo = FakeInstalledSkillRepository(skills: [Self.weather()])
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
        let repo = FakeInstalledSkillRepository(skills: [Self.weather()])
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

    @Test func aTimeoutOnALiveSocketIsALoadFailureAtOnceNotAnEmptyList() async {
        let repo = FakeInstalledSkillRepository(skills: [])
        repo.setRefreshError(Self.timedOut)
        let gateway = ScriptedConnectionManager(ScriptedConnectionManager.connected(generation: 1))
        let vm = InstalledSkillsViewModel(skills: repo, gateway: gateway, socketEndGrace: Self.longGrace)
        vm.start()
        await waitUntil { vm.phase != .loading }

        #expect(vm.phase == .loadFailed(message: Self.timedOut.errorDescription ?? ""))
        // Shown in place of the list, not again in an alert.
        #expect(vm.lastError == nil)
        vm.stop()
    }

    @Test func retryAfterAFailedFirstReadLoadsTheList() async {
        let repo = FakeInstalledSkillRepository(skills: [Self.weather()])
        repo.setRefreshError(Self.timedOut)
        let vm = InstalledSkillsViewModel(skills: repo)

        await vm.refresh()
        #expect(vm.phase == .loadFailed(message: Self.timedOut.errorDescription ?? ""))

        repo.setRefreshError(nil)
        await vm.refresh()
        #expect(vm.phase == .ready)
        #expect(vm.skills.map(\.slug) == ["weather"])
    }

    @Test func theErrorClearsAfterASuccessfulRefresh() async {
        let repo = FakeInstalledSkillRepository(skills: [Self.weather()])
        let vm = InstalledSkillsViewModel(skills: repo)
        await vm.refresh()
        #expect(vm.phase == .ready)

        repo.setRefreshError(Self.timedOut)
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
        let repo = FakeInstalledSkillRepository(skills: [Self.weather()])
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

    @Test func anAlertUpWhenTheReconnectFailsIsClearedUnderDisconnected() async {
        let repo = FakeInstalledSkillRepository(skills: [Self.weather()])
        let gateway = ScriptedConnectionManager(ScriptedConnectionManager.connected(generation: 1))
        let vm = InstalledSkillsViewModel(skills: repo, gateway: gateway)
        vm.start()
        await waitUntil { vm.phase == .ready && !vm.isRefreshing && gateway.subscriberCount == 1 }
        repo.setRefreshError(Self.timedOut)
        await vm.refresh()
        #expect(vm.lastError != nil)

        gateway.emit(ScriptedConnectionManager.connected(generation: 1, reconnecting: true))
        gateway.emit(.error(url: ScriptedConnectionManager.url, cause: Self.unreachable))
        await waitUntil { vm.phase != .ready }

        #expect(vm.phase == .disconnected(reason: Self.unreachable.errorDescription))
        #expect(vm.lastError == nil)
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
        repo.setSkills([Self.weather()])
        repo.releaseRefreshes()
        try? await Task.sleep(for: .milliseconds(50)) // room for the released read to (wrongly) land

        #expect(vm.phase == .disconnected(reason: Self.unreachable.errorDescription))
        #expect(vm.skills.isEmpty)
        #expect(!vm.isRefreshing)
        vm.stop()
    }

    @Test func reconnectingSeenBeforeTheReadFailsTheFailureIsNotShown() async {
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
        Self.expectNoFailureShown(vm)
        vm.stop()
    }

    // MARK: - Production order: the socket's end fails the read first, the manager publishes reconnecting after

    @Test func nothingLoadedReadFailsAtTheDropThenReconnectingThenTheReconnectSucceeds() async {
        let repo = FakeInstalledSkillRepository(skills: [])
        repo.holdRefreshes()
        let gateway = ScriptedConnectionManager(ScriptedConnectionManager.connected(generation: 1))
        let vm = InstalledSkillsViewModel(skills: repo, gateway: gateway, socketEndGrace: Self.longGrace)
        vm.start()
        await waitUntil { repo.refreshCount == 1 && gateway.subscriberCount == 1 }

        repo.setRefreshError(Self.unreachable)
        repo.releaseRefreshes()
        await waitUntil { !vm.isRefreshing }
        try? await Task.sleep(for: .milliseconds(100)) // the failure has landed; still nothing may show
        #expect(vm.phase == .loading)
        gateway.emit(ScriptedConnectionManager.connected(generation: 1, reconnecting: true))
        try? await Task.sleep(for: .milliseconds(50))
        #expect(vm.phase == .loading)
        repo.setRefreshError(nil)
        repo.setSkills([Self.weather()])
        gateway.emit(ScriptedConnectionManager.connected(generation: 2))
        await waitUntil { vm.phase == .ready }

        #expect(vm.skills.map(\.slug) == ["weather"])
        Self.expectNoFailureShown(vm)
        vm.stop()
    }

    @Test func nothingLoadedReadFailsAtTheDropThenReconnectingThenTheReconnectFails() async {
        let repo = FakeInstalledSkillRepository(skills: [])
        repo.holdRefreshes()
        let gateway = ScriptedConnectionManager(ScriptedConnectionManager.connected(generation: 1))
        let vm = InstalledSkillsViewModel(skills: repo, gateway: gateway, socketEndGrace: Self.longGrace)
        vm.start()
        await waitUntil { repo.refreshCount == 1 && gateway.subscriberCount == 1 }

        repo.setRefreshError(GatewayError.connectionClosed(url: ScriptedConnectionManager.url, closeCode: 1008, reason: ""))
        repo.releaseRefreshes()
        await waitUntil { !vm.isRefreshing }
        try? await Task.sleep(for: .milliseconds(100))
        #expect(vm.phase == .loading)
        gateway.emit(ScriptedConnectionManager.connected(generation: 1, reconnecting: true))
        gateway.emit(.error(url: ScriptedConnectionManager.url, cause: Self.unreachable))
        await waitUntil { vm.phase != .loading }

        #expect(vm.phase == .disconnected(reason: Self.unreachable.errorDescription))
        Self.expectNoFailureShown(vm)
        vm.stop()
    }

    @Test func rowsOnScreenRefreshFailsAtTheDropThenReconnectingThenTheReconnectSucceeds() async {
        let repo = FakeInstalledSkillRepository(skills: [Self.weather()])
        let gateway = ScriptedConnectionManager(ScriptedConnectionManager.connected(generation: 1))
        let vm = InstalledSkillsViewModel(skills: repo, gateway: gateway, socketEndGrace: Self.longGrace)
        vm.start()
        await waitUntil { vm.phase == .ready && !vm.isRefreshing && gateway.subscriberCount == 1 }
        repo.holdRefreshes()
        let refreshing = Task { await vm.refresh() }
        await waitUntil { repo.refreshCount == 2 }

        repo.setRefreshError(Self.unreachable)
        repo.releaseRefreshes()
        await waitUntil { !vm.isRefreshing }
        try? await Task.sleep(for: .milliseconds(100))
        #expect(vm.phase == .ready)
        #expect(vm.lastError == nil)
        gateway.emit(ScriptedConnectionManager.connected(generation: 1, reconnecting: true))
        await refreshing.value // the reconnecting state ends the refresh's wait
        #expect(vm.skills.map(\.slug) == ["weather"])
        repo.setRefreshError(nil)
        repo.setSkills([Self.weather(), FakeInstalledSkillRepository.skill("calendar")])
        gateway.emit(ScriptedConnectionManager.connected(generation: 2))
        await waitUntil { vm.skills.count == 2 }

        #expect(vm.phase == .ready)
        Self.expectNoFailureShown(vm)
        vm.stop()
    }

    @Test func rowsOnScreenRefreshFailsAtTheDropThenReconnectingThenTheReconnectFails() async {
        let repo = FakeInstalledSkillRepository(skills: [Self.weather()])
        let gateway = ScriptedConnectionManager(ScriptedConnectionManager.connected(generation: 1))
        let vm = InstalledSkillsViewModel(skills: repo, gateway: gateway, socketEndGrace: Self.longGrace)
        vm.start()
        await waitUntil { vm.phase == .ready && !vm.isRefreshing && gateway.subscriberCount == 1 }
        repo.holdRefreshes()
        let refreshing = Task { await vm.refresh() }
        await waitUntil { repo.refreshCount == 2 }

        repo.setRefreshError(Self.unreachable)
        repo.releaseRefreshes()
        await waitUntil { !vm.isRefreshing }
        try? await Task.sleep(for: .milliseconds(100))
        #expect(vm.lastError == nil)
        gateway.emit(ScriptedConnectionManager.connected(generation: 1, reconnecting: true))
        gateway.emit(.error(url: ScriptedConnectionManager.url, cause: Self.unreachable))
        await waitUntil { vm.phase != .ready }
        await refreshing.value

        #expect(vm.phase == .disconnected(reason: Self.unreachable.errorDescription))
        Self.expectNoFailureShown(vm)
        vm.stop()
    }

    @Test func anEndOfSocketFailureThatNoStateChangeFollowsIsShownAfterTheGracePeriod() async {
        let repo = FakeInstalledSkillRepository(skills: [])
        repo.holdRefreshes()
        let gateway = ScriptedConnectionManager(ScriptedConnectionManager.connected(generation: 1))
        let vm = InstalledSkillsViewModel(skills: repo, gateway: gateway, socketEndGrace: .milliseconds(500))
        vm.start()
        await waitUntil { repo.refreshCount == 1 && gateway.subscriberCount == 1 }

        repo.setRefreshError(Self.unreachable)
        repo.releaseRefreshes()
        await waitUntil { !vm.isRefreshing }
        #expect(vm.phase == .loading) // inside the grace period
        await waitUntil { vm.phase != .loading }

        #expect(vm.phase == .loadFailed(message: Self.unreachable.errorDescription ?? ""))
        vm.stop()
    }

    @Test func aLateAnswerFromTheOldSessionAfterTheNewSessionIsLiveIsIgnored() async {
        let repo = FakeInstalledSkillRepository(skills: [])
        repo.holdRefreshes()
        let gateway = ScriptedConnectionManager(ScriptedConnectionManager.connected(generation: 1))
        let vm = InstalledSkillsViewModel(skills: repo, gateway: gateway)
        vm.start()
        await waitUntil { repo.refreshCount == 1 && gateway.subscriberCount == 1 }

        gateway.emit(ScriptedConnectionManager.connected(generation: 1, reconnecting: true))
        gateway.emit(ScriptedConnectionManager.connected(generation: 2))
        await waitUntil { repo.refreshCount == 2 }
        repo.setSkills([FakeInstalledSkillRepository.skill("old")])
        repo.releaseOldestRefresh() // the first session's read answers now
        try? await Task.sleep(for: .milliseconds(50))
        #expect(vm.phase == .loading)
        #expect(vm.skills.isEmpty)
        repo.setSkills([FakeInstalledSkillRepository.skill("new")])
        repo.releaseOldestRefresh()
        await waitUntil { vm.phase == .ready }

        #expect(vm.skills.map(\.slug) == ["new"])
        vm.stop()
    }

    @Test func twoRapidReconnectsEndOnTheNewestSessionsList() async {
        let repo = FakeInstalledSkillRepository(skills: [FakeInstalledSkillRepository.skill("first")])
        let gateway = ScriptedConnectionManager(ScriptedConnectionManager.connected(generation: 1))
        let vm = InstalledSkillsViewModel(skills: repo, gateway: gateway)
        vm.start()
        await waitUntil { vm.phase == .ready && !vm.isRefreshing && gateway.subscriberCount == 1 }
        repo.holdRefreshes()

        gateway.emit(ScriptedConnectionManager.connected(generation: 1, reconnecting: true))
        gateway.emit(ScriptedConnectionManager.connected(generation: 2))
        await waitUntil { repo.refreshCount == 2 } // the second session's read is held first
        gateway.emit(ScriptedConnectionManager.connected(generation: 2, reconnecting: true))
        gateway.emit(ScriptedConnectionManager.connected(generation: 3))
        await waitUntil { repo.refreshCount == 3 }
        repo.setSkills([FakeInstalledSkillRepository.skill("third")])
        repo.releaseNewestRefresh() // the newest session answers first
        await waitUntil { vm.skills.map(\.slug) == ["third"] }
        repo.setSkills([FakeInstalledSkillRepository.skill("second")])
        repo.releaseRefreshes() // then the second session's late answer
        try? await Task.sleep(for: .milliseconds(50))

        #expect(vm.skills.map(\.slug) == ["third"])
        #expect(vm.phase == .ready)
        vm.stop()
    }

    @Test func neverConnectedShowsDisconnectedWithoutAReasonAndReadsNothing() async {
        let repo = FakeInstalledSkillRepository(skills: [Self.weather()])
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
        let repo = FakeInstalledSkillRepository(skills: [Self.weather()])
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
        let repo = FakeInstalledSkillRepository(skills: [Self.weather()])
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

    @Test func aDisableCutOffByTheDropReportsNothingTheReconnectThatFollowsDecides() async {
        let repo = FakeInstalledSkillRepository(skills: [Self.weather()])
        let gateway = ScriptedConnectionManager(ScriptedConnectionManager.connected(generation: 1))
        let vm = InstalledSkillsViewModel(skills: repo, gateway: gateway, socketEndGrace: Self.longGrace)
        vm.start()
        await waitUntil { vm.phase == .ready && !vm.isRefreshing && gateway.subscriberCount == 1 }
        repo.holdDisables()
        repo.setDisableError(Self.unreachable)
        vm.requestUninstall("weather")
        vm.confirmUninstall()
        await waitUntil { repo.enabledChanges.count == 1 }

        repo.releaseDisables() // the socket's end fails the pending disable
        try? await Task.sleep(for: .milliseconds(100))
        #expect(vm.uninstallingSlug == "weather")
        #expect(vm.lastError == nil)
        gateway.emit(ScriptedConnectionManager.connected(generation: 1, reconnecting: true))
        await waitUntil { vm.uninstallingSlug == nil }
        #expect(vm.phase == .ready)
        #expect(vm.lastError == nil)
        gateway.emit(.error(url: ScriptedConnectionManager.url, cause: Self.unreachable))
        await waitUntil { vm.phase != .ready }

        #expect(vm.phase == .disconnected(reason: Self.unreachable.errorDescription))
        Self.expectNoFailureShown(vm)
        vm.stop()
    }

    @Test func aDisableWhoseSocketEndedWithNoStateChangeAfterItIsReportedAfterTheGracePeriod() async {
        let repo = FakeInstalledSkillRepository(skills: [Self.weather()])
        let gateway = ScriptedConnectionManager(ScriptedConnectionManager.connected(generation: 1))
        let vm = InstalledSkillsViewModel(skills: repo, gateway: gateway, socketEndGrace: .milliseconds(300))
        vm.start()
        await waitUntil { vm.phase == .ready && !vm.isRefreshing && gateway.subscriberCount == 1 }
        repo.setDisableError(Self.unreachable)

        vm.requestUninstall("weather")
        vm.confirmUninstall()
        await waitUntil { vm.uninstallingSlug == nil }

        #expect(vm.lastError == Self.unreachable.errorDescription)
        vm.stop()
    }

    @Test func aDisableTappedDuringTheReconnectSaysItCouldNotBeSent() async {
        let repo = FakeInstalledSkillRepository(skills: [Self.weather()])
        let gateway = ScriptedConnectionManager(ScriptedConnectionManager.connected(generation: 1))
        let vm = InstalledSkillsViewModel(skills: repo, gateway: gateway, socketEndGrace: Self.longGrace)
        vm.start()
        await waitUntil { vm.phase == .ready && !vm.isRefreshing && gateway.subscriberCount == 1 }
        gateway.emit(ScriptedConnectionManager.connected(generation: 1, reconnecting: true))
        try? await Task.sleep(for: .milliseconds(50))
        // What the session lookup throws while the reconnect runs: the request never goes out.
        repo.setDisableError(Self.unreachable)

        vm.requestUninstall("weather")
        vm.confirmUninstall()
        await waitUntil { vm.uninstallingSlug == nil }

        #expect(vm.lastError == Self.unreachable.errorDescription)
        vm.stop()
    }

    @Test func aDisableEndingWithTheSessionLeavesNoRowSpinnerForTheNextSession() async {
        let repo = FakeInstalledSkillRepository(skills: [Self.weather()])
        let gateway = ScriptedConnectionManager(ScriptedConnectionManager.connected(generation: 1))
        let vm = InstalledSkillsViewModel(skills: repo, gateway: gateway)
        vm.start()
        await waitUntil { vm.phase == .ready && !vm.isRefreshing && gateway.subscriberCount == 1 }
        repo.holdDisables()
        vm.requestUninstall("weather")
        vm.confirmUninstall()
        await waitUntil { repo.enabledChanges.count == 1 }

        gateway.emit(.error(url: ScriptedConnectionManager.url, cause: Self.unreachable))
        await waitUntil { vm.phase != .ready }
        #expect(vm.uninstallingSlug == nil)
        gateway.emit(ScriptedConnectionManager.connected(generation: 2))
        await waitUntil { vm.phase == .ready }
        #expect(vm.uninstallingSlug == nil)

        repo.releaseDisables()
        vm.stop()
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
