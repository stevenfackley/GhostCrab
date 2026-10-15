import Testing
@testable import GhostCrab

@MainActor
@Suite("AIRecommendationViewModel")
struct AIRecommendationViewModelTests {

    private func makeVM(
        ai: FakeAIRecommendationService = FakeAIRecommendationService(),
        config: FakeConfigRepository = FakeConfigRepository(config: OpenClawConfig(sections: [:]))
    ) -> AIRecommendationViewModel {
        AIRecommendationViewModel(
            ai: ai,
            gateway: FakeGatewayConnectionManager(),
            config: config,
            models: FakeModelRepository(models: [])
        )
    }

    @Test func anUnavailableServiceIsReportedAsUnavailable() async {
        let vm = makeVM(ai: FakeAIRecommendationService(available: false))
        #expect(vm.available == nil)
        vm.checkAvailability()
        await vm.availabilityTask?.value
        #expect(vm.available == false)
        #expect(vm.availabilityTask == nil)
    }

    @Test func anAvailableServiceIsReportedAsAvailable() async {
        let vm = makeVM(ai: FakeAIRecommendationService(available: true))
        vm.checkAvailability()
        await vm.availabilityTask?.value
        #expect(vm.available == true)
    }

    @Test func theUnavailableErrorSaysWhyAndOffersNoInstall() {
        let error = AIRecommendationError(gatewayError: .aiServiceUnavailable(url: "ws://192.168.1.50:18789/ws"))
        #expect(error == .skillUnavailable(url: "ws://192.168.1.50:18789/ws"))
        #expect(error.headline == "AI recommendations unavailable")
        #expect(error.body.contains("no recommendation API"))
        #expect(!error.body.localizedCaseInsensitiveContains("install it"))
    }

    @Test func doubleApplyPatchWritesOnce() async {
        let change = SuggestedChange(section: "gateway", key: "port", currentValue: "18789", suggestedValue: "18790")
        let config = FakeConfigRepository(config: OpenClawConfig(sections: [:]))
        let vm = makeVM(ai: FakeAIRecommendationService(available: true, suggestedChanges: [change]), config: config)
        vm.prompt = "faster port?"
        vm.submit()
        await vm.submitTask?.value
        #expect(vm.hasSuggestedPatch)

        vm.applySuggestedPatch()
        vm.confirmApplyPatch()
        vm.confirmApplyPatch()
        await vm.applyTask?.value

        #expect(config.updatedSections == ["gateway"])
        #expect(!vm.isPresentingPatch)
        #expect(vm.applyTask == nil)
    }

    @Test func cancelInFlightClearsLoading() {
        let vm = makeVM()
        vm.prompt = "anything"
        vm.submit()
        #expect(vm.loading)

        vm.cancelInFlight()

        #expect(!vm.loading)
        #expect(vm.submitTask == nil)
    }

    @Test func aCancelledRequestLandsNothing() async {
        let vm = makeVM(ai: FakeAIRecommendationService(available: true))
        vm.prompt = "anything"
        vm.submit()
        let abandoned = vm.submitTask

        vm.cancelInFlight()
        await abandoned?.value

        #expect(vm.response == nil)
        #expect(vm.lastError == nil)
        #expect(!vm.loading)
    }

    @Test func submitAfterAnswerRunsAgain() async {
        let vm = makeVM(ai: FakeAIRecommendationService(available: true))
        vm.prompt = "first"
        vm.submit()
        await vm.submitTask?.value
        #expect(vm.submitTask == nil)

        vm.prompt = "second"
        vm.submit()
        await vm.submitTask?.value

        #expect(vm.response?.query == "second")
        #expect(!vm.loading)
    }
}
