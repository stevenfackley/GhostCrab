import Testing
@testable import GhostCrab

@MainActor
@Suite("SettingsViewModel")
struct SettingsViewModelTests {

    private func makeVM(
        settings: FakeSettingsRepository = FakeSettingsRepository(),
        onboarding: FakeOnboardingRepository = FakeOnboardingRepository(),
        profiles: FakeConnectionProfileRepository = FakeConnectionProfileRepository()
    ) -> SettingsViewModel {
        SettingsViewModel(settings: settings, onboarding: onboarding, profiles: profiles)
    }

    @Test func rapidTogglesPersistInTapOrder() async {
        // The first write is slow, so unchained writes would land as [false, true].
        let settings = FakeSettingsRepository(writeDelays: [.milliseconds(80), .zero])
        let vm = makeVM(settings: settings)

        vm.setAllowCleartextPublicIPs(true)
        vm.setAllowCleartextPublicIPs(false)
        await vm.writeTask?.value

        #expect(settings.writeLog == [true, false])
        #expect(!vm.allowCleartextPublicIPs)
    }

    @Test func doubleResetResetsOnce() async {
        let onboarding = FakeOnboardingRepository(completed: true)
        let vm = makeVM(onboarding: onboarding)

        vm.resetOnboarding()
        vm.resetOnboarding()
        await vm.resetTask?.value

        #expect(onboarding.resetCount == 1)
        #expect(vm.onboardingResetSuccess)
        #expect(vm.resetTask == nil)
    }

    // MARK: - Clear all saved gateways

    @Test func confirmedClearAllWipesProfilesAndTokensOnce() async {
        let profile = FakeConnectionProfileRepository.profile(hasToken: true)
        let profiles = FakeConnectionProfileRepository(profiles: [profile], tokens: [profile.id.uuidString: "tok_saved"])
        let vm = makeVM(profiles: profiles)

        vm.requestClearAllProfiles()
        #expect(vm.isConfirmingClearAll)
        vm.confirmClearAllProfiles()
        vm.confirmClearAllProfiles()
        #expect(vm.isClearingAll)
        await vm.clearAllTask?.value

        #expect(profiles.clearAllCount == 1)
        #expect(profiles.storedProfileCount == 0)
        #expect(profiles.storedTokenCount == 0)
        #expect(vm.clearAllSuccess)
        #expect(!vm.isConfirmingClearAll)
        #expect(!vm.isClearingAll)
        #expect(vm.lastError == nil)
    }

    @Test func cancelledClearAllTouchesNothing() {
        let profiles = FakeConnectionProfileRepository(profiles: [FakeConnectionProfileRepository.profile()])
        let vm = makeVM(profiles: profiles)

        vm.requestClearAllProfiles()
        vm.cancelClearAllProfiles()

        #expect(!vm.isConfirmingClearAll)
        #expect(vm.clearAllTask == nil)
        #expect(profiles.clearAllCount == 0)
        #expect(profiles.storedProfileCount == 1)
    }

    @Test func failedClearAllSurfacesTheErrorClassAndMessage() async {
        let profiles = FakeConnectionProfileRepository(
            profiles: [FakeConnectionProfileRepository.profile()],
            clearAllError: .api(url: "keychain://tokens", statusCode: 0)
        )
        let vm = makeVM(profiles: profiles)

        vm.requestClearAllProfiles()
        vm.confirmClearAllProfiles()
        await vm.clearAllTask?.value

        #expect(!vm.clearAllSuccess)
        #expect(vm.lastError == "Clearing saved gateways failed (GatewayError): Gateway API error at keychain://tokens: HTTP 0")
        #expect(!vm.isClearingAll)
        #expect(profiles.storedProfileCount == 1)
    }
}
