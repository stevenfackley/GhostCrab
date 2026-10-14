import Testing
import Foundation
@testable import GhostCrab

@MainActor
@Suite("ManualEntryViewModel")
struct ManualEntryViewModelTests {

    private func makeVM(
        handoff: PairingHandoff,
        gateway: FakeGatewayConnectionManager = FakeGatewayConnectionManager(),
        profiles: FakeConnectionProfileRepository = FakeConnectionProfileRepository(),
        onboarding: FakeOnboardingRepository = FakeOnboardingRepository()
    ) -> ManualEntryViewModel {
        ManualEntryViewModel(
            connectionManager: gateway,
            profileRepository: profiles,
            onboardingRepository: onboarding,
            pairingHandoff: handoff
        )
    }

    @Test func prefillPicksUpStashedPairingTokenOnce() throws {
        let handoff = PairingHandoff()
        let url = try #require(URL(string: "http://192.168.1.20:18789"))
        handoff.stash(token: "tok_123", for: url)
        let vm = makeVM(handoff: handoff)

        vm.setPrefillURL(url)
        #expect(vm.host == "192.168.1.20")
        #expect(vm.port == "18789")
        #expect(vm.token == "tok_123")

        // The screen calls setPrefillURL on every appear; nothing is left to take.
        vm.token = ""
        vm.setPrefillURL(url)
        #expect(vm.token == "")
    }

    @Test func prefillWithoutStashLeavesTokenEmpty() throws {
        let vm = makeVM(handoff: PairingHandoff())
        vm.setPrefillURL(try #require(URL(string: "https://gw.example.com:8443")))
        #expect(vm.useHttps)
        #expect(vm.port == "8443")
        #expect(vm.token == "")
    }

    @Test func prefillForAnotherGatewayDoesNotGetTheToken() throws {
        let handoff = PairingHandoff()
        handoff.stash(token: "tok_123", for: try #require(URL(string: "http://192.168.1.20:18789")))
        let vm = makeVM(handoff: handoff)
        vm.setPrefillURL(try #require(URL(string: "http://192.168.1.99:18789")))
        #expect(vm.token == "")
    }

    @Test func prefillNeverConnects() throws {
        let handoff = PairingHandoff()
        let gateway = FakeGatewayConnectionManager()
        let url = try #require(URL(string: "http://192.168.1.20:18789"))
        handoff.stash(token: "tok_123", for: url)
        let vm = makeVM(handoff: handoff, gateway: gateway)

        vm.setPrefillURL(url)

        #expect(gateway.connectURLs.isEmpty)
        #expect(!vm.isConnecting)
    }

    @Test func warnsWhenATSWillBlockPlainHTTPToAHostname() {
        let vm = makeVM(handoff: PairingHandoff())
        vm.setHost("box.tailnet-1234.ts.net")
        #expect(vm.cleartextBlockedByATS)

        vm.useHttps = true
        #expect(!vm.cleartextBlockedByATS)

        vm.useHttps = false
        vm.setHost("192.168.1.20")
        #expect(!vm.cleartextBlockedByATS)
    }
}
