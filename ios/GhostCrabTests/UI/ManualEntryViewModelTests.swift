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

    // MARK: - Pasted links (Mac Catalyst has no QR scanner)

    @Test func pastedPairingLinkFillsTheFormThroughTheHandoff() throws {
        let handoff = PairingHandoff()
        let gateway = FakeGatewayConnectionManager()
        let vm = makeVM(handoff: handoff, gateway: gateway)
        vm.pastedLink = "  " + PairingLinkFixture.link(
            json: #"{"v":1,"url":"http://192.168.1.20:18789","token":"tok_123","name":"Basement"}"#
        ) + "\n"

        vm.applyPastedLink()

        #expect(!vm.useHttps)
        #expect(vm.host == "192.168.1.20")
        #expect(vm.port == "18789")
        #expect(vm.token == "tok_123")
        #expect(vm.pairedName == "Basement")
        #expect(vm.pastedLinkError == nil)
        // The field held the token: cleared once applied.
        #expect(vm.pastedLink == "")
        // One-shot hand-off, already taken; and a paste never connects.
        #expect(handoff.takePairing(for: try #require(URL(string: "http://192.168.1.20:18789"))) == nil)
        #expect(gateway.connectURLs.isEmpty)
    }

    @Test func pastedGatewayURLFillsSchemeHostAndPort() {
        let vm = makeVM(handoff: PairingHandoff())
        vm.pastedLink = "https://gw.example.com:8443/admin?x=1"

        vm.applyPastedLink()

        #expect(vm.useHttps)
        #expect(vm.host == "gw.example.com")
        #expect(vm.port == "8443")
        #expect(vm.token == "")
        #expect(vm.pastedLinkError == nil)
        #expect(vm.pastedLink == "")
    }

    @Test func malformedPastedPairingLinkLeavesTheFormAlone() {
        let vm = makeVM(handoff: PairingHandoff())
        vm.setHost("192.168.1.5")
        let link = PairingLinkFixture.link(json: #"{"v":2,"url":"http://192.168.1.20:18789","token":"tok_123"}"#)
        vm.pastedLink = link

        vm.applyPastedLink()

        #expect(vm.pastedLinkError == "Pairing link is malformed: unsupported pairing data version 2")
        #expect(vm.host == "192.168.1.5")
        #expect(vm.token == "")
        #expect(vm.pastedLink == link)
    }

    @Test(arguments: ["gw.example.com", "http://admin:hunter2@192.168.1.20:18789", "ftp://192.168.1.20"])
    func pastedTextThatIsNotAGatewayURLIsRejected(text: String) {
        let vm = makeVM(handoff: PairingHandoff())
        vm.pastedLink = text

        vm.applyPastedLink()

        #expect(vm.pastedLinkError == "Not an http(s) gateway URL or a ghostcrab://pair link")
        #expect(vm.host == "")
    }

    @Test func emptyPasteAsksForALink() {
        let vm = makeVM(handoff: PairingHandoff())
        vm.pastedLink = "  \n"

        vm.applyPastedLink()

        #expect(vm.pastedLinkError == "Paste a gateway URL or a ghostcrab://pair link first")
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

    // MARK: - In-flight guard

    @Test func doubleTapConnectsOnceAndSavesProfileWithToken() async {
        let gateway = FakeGatewayConnectionManager()
        let profiles = FakeConnectionProfileRepository()
        let onboarding = FakeOnboardingRepository()
        let vm = makeVM(handoff: PairingHandoff(), gateway: gateway, profiles: profiles, onboarding: onboarding)
        vm.setHost("192.168.1.20")
        vm.token = "tok_123"

        vm.connect()
        vm.connect()
        await vm.connectTask?.value

        #expect(gateway.connectURLs == ["http://192.168.1.20:18789"])
        #expect(profiles.savedTokens == ["tok_123"])
        #expect(profiles.savedProfiles.first?.displayName == "Fake Gateway")
        #expect(onboarding.markCompletedCount == 1)
        #expect(vm.navigateToDashboard)
        #expect(!vm.isConnecting)
        #expect(vm.connectTask == nil)
    }

    @Test func invalidHostNeverReachesTheGateway() {
        let gateway = FakeGatewayConnectionManager()
        let vm = makeVM(handoff: PairingHandoff(), gateway: gateway)
        vm.setHost("http://192.168.1.20")

        vm.connect()

        #expect(vm.hostError == "Remove scheme — use the HTTPS toggle instead")
        #expect(vm.connectTask == nil)
        #expect(gateway.connectURLs.isEmpty)
    }

    @Test func leavingTheScreenAbandonsTheConnect() async {
        let profiles = FakeConnectionProfileRepository()
        let onboarding = FakeOnboardingRepository()
        let vm = makeVM(handoff: PairingHandoff(), profiles: profiles, onboarding: onboarding)
        vm.setHost("192.168.1.20")

        vm.connect()
        let task = vm.connectTask
        vm.cancelConnect()
        await task?.value

        #expect(vm.connectTask == nil)
        #expect(!vm.isConnecting)
        #expect(vm.lastError == nil)
        #expect(!vm.navigateToDashboard)
        #expect(profiles.savedProfiles.isEmpty)
        #expect(onboarding.markCompletedCount == 0)
    }

    @Test func cancelWithNothingInFlightIsANoOp() async {
        let gateway = FakeGatewayConnectionManager()
        let vm = makeVM(handoff: PairingHandoff(), gateway: gateway)
        vm.setHost("192.168.1.20")

        vm.cancelConnect()
        vm.connect()
        await vm.connectTask?.value

        #expect(gateway.connectURLs == ["http://192.168.1.20:18789"])
        #expect(vm.navigateToDashboard)
    }
}
