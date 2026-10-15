import Testing
import Foundation
@testable import GhostCrab

@MainActor
@Suite("ModelManagerViewModel activation")
struct ModelManagerViewModelTests {

    private func makeVM(setActiveError: GatewayError? = nil) async -> (ModelManagerViewModel, FakeModelRepository) {
        let repo = FakeModelRepository(
            models: [FakeModelRepository.model("m1", active: true), FakeModelRepository.model("m2")],
            setActiveError: setActiveError
        )
        let vm = ModelManagerViewModel(models: repo)
        await vm.load()
        return (vm, repo)
    }

    @Test func tapOnInactiveModelAsksForConfirmation() async {
        let (vm, repo) = await makeVM()
        vm.requestActivation("m2")
        #expect(vm.pendingActivation?.id == "m2")
        #expect(repo.setActiveCalls.isEmpty)
    }

    @Test func tapOnActiveModelDoesNothing() async {
        let (vm, _) = await makeVM()
        vm.requestActivation("m1")
        #expect(vm.pendingActivation == nil)
    }

    @Test func cancelClearsPendingWithoutCallingGateway() async {
        let (vm, repo) = await makeVM()
        vm.requestActivation("m2")
        vm.cancelActivation()
        #expect(vm.pendingActivation == nil)
        #expect(repo.setActiveCalls.isEmpty)
    }

    @Test func doubleConfirmActivatesExactlyOnce() async throws {
        let (vm, repo) = await makeVM()
        vm.requestActivation("m2")
        let target = try #require(vm.pendingActivation)

        vm.confirmActivation(target)
        vm.confirmActivation(target)
        await vm.activationTask?.value

        #expect(repo.setActiveCalls == ["m2"])
        #expect(vm.models_.first(where: { $0.isActive })?.id == "m2")
        #expect(vm.pendingActivation == nil)
    }

    @Test func aGatewayWithoutModelsListIsNotSupportedNotAnError() async {
        let repo = FakeModelRepository(
            models: [],
            getModelsError: .methodUnsupported(url: "ws://192.168.1.20:18789/ws", method: "models.list")
        )
        let vm = ModelManagerViewModel(models: repo)
        await vm.load()
        #expect(vm.notSupported)
        #expect(vm.lastError == nil)
        #expect(vm.models_.isEmpty)
    }

    @Test func anUnlistedPrimaryIsExposedWithNothingActive() async {
        let repo = FakeModelRepository(
            models: [FakeModelRepository.model("m1"), FakeModelRepository.model("m2")],
            unlistedPrimary: "anthropic/claude-opus-9"
        )
        let vm = ModelManagerViewModel(models: repo)
        await vm.load()
        #expect(vm.unlistedPrimary == "anthropic/claude-opus-9")
        #expect(vm.models_.map(\.id) == ["m1", "m2"])
        #expect(vm.models_.allSatisfy { !$0.isActive })
    }

    @Test func aMatchedPrimaryShowsNoBanner() async {
        let (vm, _) = await makeVM()
        #expect(vm.unlistedPrimary == nil)
    }

    @Test func otherLoadFailuresAreErrorsNotUnsupported() async {
        let repo = FakeModelRepository(
            models: [],
            getModelsError: .missingScope(url: "ws://192.168.1.20:18789/ws", method: "models.list", scope: "operator.read")
        )
        let vm = ModelManagerViewModel(models: repo)
        await vm.load()
        #expect(!vm.notSupported)
        #expect(vm.lastError != nil)
    }

    @Test func failedActivationKeepsTheErrorVisibleAfterReload() async throws {
        let (vm, repo) = await makeVM(
            setActiveError: .api(url: "http://192.168.1.20:18789/api/models/active", statusCode: 404)
        )
        vm.requestActivation("m2")
        let target = try #require(vm.pendingActivation)

        vm.confirmActivation(target)
        await vm.activationTask?.value

        #expect(repo.setActiveCalls == ["m2"])
        #expect(vm.lastError?.hasPrefix("Failed to activate M2") == true)
        #expect(vm.models_.first(where: { $0.isActive })?.id == "m1")
    }
}
