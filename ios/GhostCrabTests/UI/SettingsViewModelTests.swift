import Testing
@testable import GhostCrab

@MainActor
@Suite("SettingsViewModel")
struct SettingsViewModelTests {

    @Test func rapidTogglesPersistInTapOrder() async {
        // The first write is slow, so unchained writes would land as [false, true].
        let settings = FakeSettingsRepository(writeDelays: [.milliseconds(80), .zero])
        let vm = SettingsViewModel(settings: settings, onboarding: FakeOnboardingRepository())

        vm.setAllowCleartextPublicIPs(true)
        vm.setAllowCleartextPublicIPs(false)
        await vm.writeTask?.value

        #expect(settings.writeLog == [true, false])
        #expect(!vm.allowCleartextPublicIPs)
    }

    @Test func doubleResetResetsOnce() async {
        let onboarding = FakeOnboardingRepository(completed: true)
        let vm = SettingsViewModel(settings: FakeSettingsRepository(), onboarding: onboarding)

        vm.resetOnboarding()
        vm.resetOnboarding()
        await vm.resetTask?.value

        #expect(onboarding.resetCount == 1)
        #expect(vm.onboardingResetSuccess)
        #expect(vm.resetTask == nil)
    }
}
