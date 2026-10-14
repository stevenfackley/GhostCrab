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

    @Test func pairedNameBecomesTheSavedProfileName() async throws {
        let handoff = PairingHandoff()
        let profiles = FakeConnectionProfileRepository()
        let url = try #require(URL(string: "http://192.168.1.20:18789"))
        handoff.stash(token: "tok_123", displayName: "  Basement GW \n", for: url)
        let vm = makeVM(handoff: handoff, profiles: profiles)

        vm.setPrefillURL(url)
        #expect(vm.pairedName == "Basement GW")

        vm.connect()
        await vm.connectTask?.value

        #expect(profiles.savedProfiles.map(\.displayName) == ["Basement GW"])
        #expect(profiles.savedTokens == ["tok_123"])
    }

    @Test func blankPairedNameFallsBackToTheGatewayName() async throws {
        let handoff = PairingHandoff()
        let profiles = FakeConnectionProfileRepository()
        let url = try #require(URL(string: "http://192.168.1.20:18789"))
        handoff.stash(token: nil, displayName: "   ", for: url)
        let vm = makeVM(handoff: handoff, profiles: profiles)

        vm.setPrefillURL(url)
        #expect(vm.pairedName == nil)

        vm.connect()
        await vm.connectTask?.value

        #expect(profiles.savedProfiles.map(\.displayName) == ["Fake Gateway"])
    }

    // MARK: - IPv6 hosts

    @Test func bracketedIPv6LiteralConnects() async {
        let gateway = FakeGatewayConnectionManager()
        let vm = makeVM(handoff: PairingHandoff(), gateway: gateway)
        vm.setHost("[::1]")

        vm.connect()
        #expect(vm.hostError == nil)
        await vm.connectTask?.value

        #expect(gateway.connectURLs == ["http://[::1]:18789"])
    }

    @Test func ipv6ZoneIdIsDroppedFromTheURL() async {
        let gateway = FakeGatewayConnectionManager()
        let vm = makeVM(handoff: PairingHandoff(), gateway: gateway)
        vm.setHost("fe80::1%en0")
        #expect(vm.assembledURL == "http://[fe80::1]:18789")

        vm.connect()
        #expect(vm.hostError == nil)
        await vm.connectTask?.value

        #expect(gateway.connectURLs == ["http://[fe80::1]:18789"])
    }

    @Test func ipv6PairingPrefillConnects() async throws {
        let gateway = FakeGatewayConnectionManager()
        let vm = makeVM(handoff: PairingHandoff(), gateway: gateway)
        vm.setPrefillURL(try #require(URL(string: "http://[fe80::1]:18789")))

        vm.connect()
        #expect(vm.hostError == nil)
        await vm.connectTask?.value

        #expect(gateway.connectURLs == ["http://[fe80::1]:18789"])
    }

    @Test(arguments: ["gw:8080", "192.168.1.20:18789"])
    func hostWithAPortIsRejected(host: String) {
        let gateway = FakeGatewayConnectionManager()
        let vm = makeVM(handoff: PairingHandoff(), gateway: gateway)
        vm.setHost(host)

        vm.connect()

        #expect(vm.hostError == "Port goes in the Port field, not the Host field")
        #expect(vm.connectTask == nil)
        #expect(gateway.connectURLs.isEmpty)
    }

    @Test func zoneIdOnANonIPv6HostIsRejected() {
        let vm = makeVM(handoff: PairingHandoff())
        vm.setHost("gateway%en0")
        vm.connect()
        #expect(vm.hostError == "Host must not contain '%' (IPv6 zone ids only)")
    }
}
