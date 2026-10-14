import Testing
@testable import GhostCrab

@MainActor
@Suite("OnboardingViewModel")
struct OnboardingViewModelTests {

    @Test func rapidNextAdvancesExactlyOneStep() async {
        let repo = FakeOnboardingRepository()
        let vm = OnboardingViewModel(repository: repo)
        await vm.loadTask?.value

        vm.next()
        vm.next()
        await vm.transitionTask?.value

        #expect(vm.step == .whatIsOpenClaw)
        #expect(repo.savedSteps == [.whatIsOpenClaw])
    }

    @Test func nextAfterATransitionLandsAdvancesAgain() async {
        let repo = FakeOnboardingRepository()
        let vm = OnboardingViewModel(repository: repo)
        await vm.loadTask?.value

        vm.next()
        await vm.transitionTask?.value
        vm.next()
        await vm.transitionTask?.value
        vm.back()
        await vm.transitionTask?.value

        #expect(vm.step == .whatIsOpenClaw)
        #expect(repo.savedSteps == [.whatIsOpenClaw, .installGateway, .whatIsOpenClaw])
        #expect(vm.transitionTask == nil)
    }

    @Test func rapidSkipCompletesOnce() async {
        let repo = FakeOnboardingRepository()
        let vm = OnboardingViewModel(repository: repo)

        vm.skip()
        vm.skip()
        await vm.transitionTask?.value

        #expect(vm.isFinished)
        #expect(repo.markCompletedCount == 1)
    }

    @Test func hydrationNeverOverridesAnEarlyTap() async {
        let repo = FakeOnboardingRepository(savedStep: .startGateway)
        let vm = OnboardingViewModel(repository: repo)

        vm.next()  // before hydration has run
        await vm.loadTask?.value
        await vm.transitionTask?.value

        #expect(vm.step == .whatIsOpenClaw)
    }

    @Test func hydrationRestoresTheSavedStep() async {
        let vm = OnboardingViewModel(repository: FakeOnboardingRepository(savedStep: .startGateway))
        await vm.loadTask?.value
        #expect(vm.step == .startGateway)
    }
}
