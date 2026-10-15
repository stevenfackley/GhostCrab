import Testing
@testable import GhostCrab

@MainActor
@Suite("DashboardViewModel")
struct DashboardViewModelTests {

    @Test func secondDisconnectIsIgnored() async {
        let gateway = FakeGatewayConnectionManager()
        let vm = DashboardViewModel(gateway: gateway, models: FakeModelRepository(models: []), ai: FakeAIRecommendationService())

        let first = await vm.disconnect()
        let second = await vm.disconnect()

        #expect(first)
        #expect(!second)
        #expect(vm.isDisconnecting)
        #expect(gateway.disconnectCount == 1)
    }

    @Test func repeatedAppearDoesNotStackLoads() async {
        let models = FakeModelRepository(models: [FakeModelRepository.model("m1", active: true)])
        let vm = DashboardViewModel(gateway: FakeGatewayConnectionManager(), models: models, ai: FakeAIRecommendationService(available: true))

        vm.onAppear()
        vm.onAppear()
        await vm.loadTask?.value
        await vm.aiTask?.value

        #expect(models.getModelsCount == 1)
        #expect(vm.activeModel?.id == "m1")
        #expect(vm.aiAvailable)
        #expect(vm.loadTask == nil)
        #expect(vm.aiTask == nil)
    }

    @Test func aDeviceTokenThatCouldNotBeSavedIsShown() async {
        let gateway = FakeGatewayConnectionManager(warnings: [.deviceTokenNotSaved])
        let vm = DashboardViewModel(gateway: gateway, models: FakeModelRepository(models: []), ai: FakeAIRecommendationService())

        vm.onAppear()
        await vm.loadTask?.value

        #expect(vm.warnings == [.deviceTokenNotSaved])
        #expect(ConnectionWarning.deviceTokenNotSaved.userMessage.contains("gateway token"))
    }

    @Test func noWarningsShowNothing() async {
        let vm = DashboardViewModel(gateway: FakeGatewayConnectionManager(), models: FakeModelRepository(models: []), ai: FakeAIRecommendationService())

        vm.onAppear()
        await vm.loadTask?.value

        #expect(vm.warnings.isEmpty)
    }

    @Test func leavingCancelsTheLoadAndReturningReloads() async {
        let models = FakeModelRepository(models: [FakeModelRepository.model("m1", active: true)])
        let vm = DashboardViewModel(gateway: FakeGatewayConnectionManager(), models: models, ai: FakeAIRecommendationService())

        vm.onAppear()
        let abandoned = vm.loadTask
        vm.onDisappear()
        #expect(vm.loadTask == nil)
        await abandoned?.value
        // The cancelled load applies nothing.
        #expect(vm.activeModel == nil)
        #expect(vm.lastError == nil)

        vm.onAppear()
        await vm.loadTask?.value

        #expect(vm.activeModel?.id == "m1")
        #expect(vm.modelCount == 1)
    }
}
