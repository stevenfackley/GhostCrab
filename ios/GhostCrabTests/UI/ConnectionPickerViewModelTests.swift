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

    // MARK: - Device approval (PAIRING_REQUIRED)

    private static let pending = GatewayError.pairingRequired(
        url: "ws://192.168.1.20:18789/ws", requestId: "req-8", reason: "scope-upgrade", requestedScopes: []
    )

    @Test(.timeLimit(.minutes(1)))
    func pairingRequiredWaitsForApprovalThenNavigates() async {
        let profile = FakeConnectionProfileRepository.profile()
        let gateway = FakeGatewayConnectionManager(failFirst: [Self.pending])
        let vm = ConnectionPickerViewModel(
            profileRepository: FakeConnectionProfileRepository(profiles: [profile]),
            connectionManager: gateway,
            pairingRetryInterval: .seconds(60)
        )

        vm.connect(profile)
        while vm.pairingPrompt == nil { await Task.yield() }
        #expect(vm.pairingPrompt?.approveCommand == "openclaw devices approve req-8")
        #expect(vm.connectingId == profile.id)

        vm.checkPairingNow()
        await vm.connectTask?.value
        #expect(vm.navigateToDashboard)
        #expect(vm.pairingPrompt == nil)
        #expect(gateway.connectURLs == [profile.url, profile.url])
    }

    @Test(.timeLimit(.minutes(1)))
    func cancelPairingStopsWaiting() async {
        let profile = FakeConnectionProfileRepository.profile()
        let gateway = FakeGatewayConnectionManager(connectError: Self.pending)
        let vm = ConnectionPickerViewModel(
            profileRepository: FakeConnectionProfileRepository(profiles: [profile]),
            connectionManager: gateway,
            pairingRetryInterval: .seconds(60)
        )
        vm.connect(profile)
        while vm.pairingPrompt == nil { await Task.yield() }
        let waiting = vm.connectTask

        vm.cancelPairing()
        #expect(vm.pairingPrompt == nil)
        #expect(vm.connectingId == nil)
        #expect(vm.connectTask == nil)
        #expect(vm.lastError == nil)
        #expect(!vm.navigateToDashboard)

        await waiting?.value
        #expect(gateway.disconnectCount == 1)
        #expect(gateway.connectURLs == [profile.url])
    }

    @Test(.timeLimit(.minutes(1)))
    func profileRowsAreInertWhileTheApprovalCardShows() async {
        let profile = FakeConnectionProfileRepository.profile()
        let gateway = FakeGatewayConnectionManager(connectError: Self.pending)
        let vm = ConnectionPickerViewModel(
            profileRepository: FakeConnectionProfileRepository(profiles: [profile]),
            connectionManager: gateway,
            pairingRetryInterval: .seconds(60)
        )
        #expect(vm.profilesSelectable)

        vm.connect(profile)
        #expect(!vm.profilesSelectable)
        while vm.pairingPrompt == nil { await Task.yield() }
        #expect(!vm.profilesSelectable)

        vm.cancelPairing()
        #expect(vm.profilesSelectable)
    }

    @Test func theSavedTokenIsReadOnceForTheWholeWait() async {
        let profile = FakeConnectionProfileRepository.profile(hasToken: true)
        let profiles = FakeConnectionProfileRepository(profiles: [profile], tokens: [profile.id.uuidString: "tok_saved"])
        let gateway = FakeGatewayConnectionManager(failFirst: [Self.pending, Self.pending])
        let vm = ConnectionPickerViewModel(
            profileRepository: profiles, connectionManager: gateway, pairingRetryInterval: .milliseconds(10)
        )

        vm.connect(profile)
        await vm.connectTask?.value

        #expect(vm.navigateToDashboard)
        #expect(profiles.getTokenCount == 1)
        #expect(gateway.connectTokens == ["tok_saved", "tok_saved", "tok_saved"])
    }

    @Test func noApprovalInTimeShowsTheNextStep() async {
        let profile = FakeConnectionProfileRepository.profile()
        let gateway = FakeGatewayConnectionManager(connectError: Self.pending)
        let vm = ConnectionPickerViewModel(
            profileRepository: FakeConnectionProfileRepository(profiles: [profile]),
            connectionManager: gateway,
            pairingRetryInterval: .milliseconds(10),
            pairingTimeout: .milliseconds(60)
        )

        vm.connect(profile)
        await vm.connectTask?.value

        #expect(vm.lastError == PairingApproval.timeoutMessage)
        #expect(vm.pairingPrompt == nil)
        #expect(vm.connectingId == nil)
        #expect(gateway.disconnectCount == 1)
    }

    @Test func aConnectSupersededByAnotherScreenShowsNoError() async {
        let profile = FakeConnectionProfileRepository.profile()
        let vm = ConnectionPickerViewModel(
            profileRepository: FakeConnectionProfileRepository(profiles: [profile]),
            connectionManager: FakeGatewayConnectionManager(failFirst: [CancellationError()])
        )

        vm.connect(profile)
        await vm.connectTask?.value

        #expect(vm.lastError == nil)
        #expect(vm.connectingId == nil)
        #expect(!vm.navigateToDashboard)
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
