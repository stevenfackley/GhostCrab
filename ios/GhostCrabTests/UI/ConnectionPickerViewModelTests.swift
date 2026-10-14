import Testing
import Foundation
@testable import GhostCrab

@MainActor
@Suite("ConnectionPickerViewModel")
struct ConnectionPickerViewModelTests {

    @Test func doubleTapConnectsOnceWithTheSavedToken() async {
        let profile = FakeConnectionProfileRepository.profile(hasToken: true)
        let profiles = FakeConnectionProfileRepository(profiles: [profile], tokens: [profile.id.uuidString: "tok_saved"])
        let gateway = FakeGatewayConnectionManager()
        let vm = ConnectionPickerViewModel(profileRepository: profiles, connectionManager: gateway)

        vm.connect(profile)
        vm.connect(profile)
        await vm.connectTask?.value

        #expect(gateway.connectURLs == [profile.url])
        #expect(gateway.connectTokens == ["tok_saved"])
        #expect(vm.navigateToDashboard)
        #expect(vm.connectingId == nil)
        #expect(vm.connectTask == nil)
    }

    @Test func failedConnectSurfacesErrorAndClearsSpinner() async {
        let profile = FakeConnectionProfileRepository.profile()
        let gateway = FakeGatewayConnectionManager(connectError: .unreachable(url: profile.url))
        let vm = ConnectionPickerViewModel(
            profileRepository: FakeConnectionProfileRepository(profiles: [profile]),
            connectionManager: gateway
        )

        vm.connect(profile)
        await vm.connectTask?.value

        #expect(vm.lastError == "Gateway unreachable at \(profile.url)")
        #expect(!vm.navigateToDashboard)
        #expect(vm.connectingId == nil)
    }

    @Test func connectAgainAfterAFailureIsAllowed() async {
        let profile = FakeConnectionProfileRepository.profile()
        let gateway = FakeGatewayConnectionManager(connectError: .unreachable(url: profile.url))
        let vm = ConnectionPickerViewModel(
            profileRepository: FakeConnectionProfileRepository(profiles: [profile]),
            connectionManager: gateway
        )

        vm.connect(profile)
        await vm.connectTask?.value
        vm.connect(profile)
        await vm.connectTask?.value

        #expect(gateway.connectURLs == [profile.url, profile.url])
    }

    @Test func doubleSwipeDeletesOnceAndSurfacesFailure() async {
        let profile = FakeConnectionProfileRepository.profile()
        let profiles = FakeConnectionProfileRepository(
            profiles: [profile],
            deleteError: .api(url: "keychain://token", statusCode: 0)
        )
        let vm = ConnectionPickerViewModel(profileRepository: profiles, connectionManager: FakeGatewayConnectionManager())

        vm.delete(profile.id)
        vm.delete(profile.id)
        await vm.deleteTask?.value

        #expect(profiles.deleteAttempts == [profile.id.uuidString])
        #expect(vm.lastError?.hasPrefix("Failed to delete") == true)
    }
}
