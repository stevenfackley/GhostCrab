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
        onboarding: FakeOnboardingRepository = FakeOnboardingRepository(),
        pairingRetryInterval: Duration = PairingApproval.retryInterval,
        pairingTimeout: Duration = PairingApproval.timeout
    ) -> ManualEntryViewModel {
        ManualEntryViewModel(
            connectionManager: gateway,
            profileRepository: profiles,
            onboardingRepository: onboarding,
            pairingHandoff: handoff,
            pairingRetryInterval: pairingRetryInterval,
            pairingTimeout: pairingTimeout
        )
    }

    private static let pending = GatewayError.pairingRequired(
        url: "ws://192.168.1.20:18789/ws", requestId: "req-7", reason: "not-paired", requestedScopes: ["operator.read"]
    )

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
        // The rejected link still held a token: the field is cleared anyway.
        #expect(vm.pastedLink == "")
    }

    @Test func oversizedPasteIsRejectedAndCleared() {
        let vm = makeVM(handoff: PairingHandoff())
        vm.pastedLink = String(repeating: "a", count: PairingLink.maxLength + 1)

        vm.applyPastedLink()

        #expect(vm.pastedLinkError == "Link is too large (over \(PairingLink.maxLength) characters)")
        #expect(vm.pastedLink == "")
    }

    @Test(arguments: ["gw.example.com", "http://admin:hunter2@192.168.1.20:18789", "ftp://192.168.1.20"])
    func pastedTextThatIsNotAGatewayURLIsRejected(text: String) {
        let vm = makeVM(handoff: PairingHandoff())
        vm.pastedLink = text

        vm.applyPastedLink()

        #expect(vm.pastedLinkError == "Not an http(s) gateway URL or a ghostcrab://pair link")
        #expect(vm.host == "")
        // Could be userinfo credentials: never left in the field.
        #expect(vm.pastedLink == "")
    }

    @Test func aPastedSetupCodeIsExplainedAndFillsNothing() {
        let vm = makeVM(handoff: PairingHandoff())
        vm.pastedLink = "oc-pair://eyJ1cmwiOiJ3czovLzE3Mi4xNy4wLjI6MTg3ODkiLCJib290c3RyYXBUb2tlbiI6ImJ0LXRlc3QtMDAwMSIsImV4cGlyZXNBdE1zIjoxNzkyMDI4MDAwMDAwfQ"

        vm.applyPastedLink()

        #expect(vm.pastedLinkError?.contains("openclaw devices approve") == true)
        #expect(vm.pastedLinkError?.contains("bt-test-0001") == false)
        #expect(vm.host == "")
        #expect(vm.token == "")
        #expect(vm.pastedLink == "")
    }

    @Test func aPastedWsURLFillsSchemeHostAndPort() {
        let vm = makeVM(handoff: PairingHandoff())
        vm.pastedLink = "wss://gw.example.com/ws"

        vm.applyPastedLink()

        #expect(vm.pastedLinkError == nil)
        #expect(vm.useHttps)
        #expect(vm.host == "gw.example.com")
        #expect(vm.port == "443")
    }

    @Test func emptyPasteAsksForALink() {
        let vm = makeVM(handoff: PairingHandoff())
        vm.pastedLink = "  \n"

        vm.applyPastedLink()

        #expect(vm.pastedLinkError == "Paste a gateway URL or a ghostcrab://pair link first")
    }

    // MARK: - A token never follows the form to another gateway

    @Test func pastingAPlainURLAfterAPairingLinkDropsTheFirstToken() async {
        let gateway = FakeGatewayConnectionManager()
        let vm = makeVM(handoff: PairingHandoff(), gateway: gateway)
        vm.pastedLink = PairingLinkFixture.link(
            json: #"{"v":1,"url":"http://192.168.1.20:18789","token":"tok_A","name":"Gateway A"}"#
        )
        vm.applyPastedLink()
        #expect(vm.token == "tok_A")
        #expect(vm.pairedName == "Gateway A")

        vm.pastedLink = "http://192.168.1.99:18789"
        vm.applyPastedLink()

        #expect(vm.host == "192.168.1.99")
        #expect(vm.token == "")
        #expect(vm.pairedName == nil)

        vm.connect()
        await vm.connectTask?.value
        #expect(gateway.connectURLs == ["http://192.168.1.99:18789"])
        #expect(gateway.connectTokens == [nil])
    }

    @Test func aTokenlessLinkAfterAPairingLinkDropsTheFirstToken() {
        let vm = makeVM(handoff: PairingHandoff())
        vm.pastedLink = PairingLinkFixture.link(json: #"{"v":1,"url":"http://192.168.1.20:18789","token":"tok_A"}"#)
        vm.applyPastedLink()
        #expect(vm.token == "tok_A")

        vm.pastedLink = PairingLinkFixture.link(json: #"{"v":1,"url":"http://192.168.1.99:18789"}"#)
        vm.applyPastedLink()

        #expect(vm.host == "192.168.1.99")
        #expect(vm.token == "")
        #expect(vm.pairedName == nil)
    }

    @Test func aTypedTokenIsDroppedWhenAPasteSwitchesGateways() {
        let vm = makeVM(handoff: PairingHandoff())
        vm.setHost("192.168.1.20")
        vm.token = "tok_typed"

        vm.pastedLink = "http://192.168.1.99:18789"
        vm.applyPastedLink()

        #expect(vm.token == "")
    }

    @Test func aSecondLinkForAnotherGatewayBringsOnlyItsOwnToken() {
        let vm = makeVM(handoff: PairingHandoff())
        vm.pastedLink = PairingLinkFixture.link(json: #"{"v":1,"url":"http://192.168.1.20:18789","token":"tok_A"}"#)
        vm.applyPastedLink()

        vm.pastedLink = PairingLinkFixture.link(json: #"{"v":1,"url":"http://192.168.1.99:18789","token":"tok_B"}"#)
        vm.applyPastedLink()

        #expect(vm.host == "192.168.1.99")
        #expect(vm.token == "tok_B")
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

    @Test func leavingAfterTheGatewayAcceptedDisconnects() async {
        let gateway = FakeGatewayConnectionManager()
        let profiles = FakeConnectionProfileRepository()
        let vm = makeVM(handoff: PairingHandoff(), gateway: gateway, profiles: profiles)
        vm.setHost("192.168.1.20")

        vm.connect()
        let task = vm.connectTask
        // The fake's connect ignores cancellation and succeeds, so the cancel
        // lands after the gateway accepted the session.
        vm.cancelConnect()
        await task?.value

        #expect(gateway.connectURLs == ["http://192.168.1.20:18789"])
        #expect(gateway.disconnectCount == 1)
        guard case .disconnected = gateway.currentState else {
            Issue.record("expected .disconnected, got \(gateway.currentState)")
            return
        }
        #expect(profiles.savedProfiles.isEmpty)
        #expect(vm.lastError == nil)
        #expect(!vm.navigateToDashboard)
    }

    // MARK: - Device approval (PAIRING_REQUIRED)

    @Test(.timeLimit(.minutes(1)))
    func pairingRequiredShowsTheCommandThenConnectsOnCheckNow() async {
        let gateway = FakeGatewayConnectionManager(failFirst: [Self.pending])
        let profiles = FakeConnectionProfileRepository()
        let vm = makeVM(handoff: PairingHandoff(), gateway: gateway, profiles: profiles, pairingRetryInterval: .seconds(60))
        vm.setHost("192.168.1.20")

        vm.connect()
        while vm.pairingPrompt == nil { await Task.yield() }
        #expect(vm.pairingPrompt?.approveCommand == "openclaw devices approve req-7")
        #expect(vm.isConnecting)
        #expect(vm.lastError == nil)

        vm.checkPairingNow()
        await vm.connectTask?.value
        #expect(vm.navigateToDashboard)
        #expect(vm.pairingPrompt == nil)
        #expect(gateway.connectURLs.count == 2)
        #expect(profiles.savedProfiles.count == 1)
    }

    @Test func noApprovalInTimeShowsTheNextStep() async {
        let gateway = FakeGatewayConnectionManager(connectError: Self.pending)
        let vm = makeVM(handoff: PairingHandoff(), gateway: gateway, pairingRetryInterval: .milliseconds(10), pairingTimeout: .milliseconds(60))
        vm.setHost("192.168.1.20")
        vm.connect()
        await vm.connectTask?.value
        #expect(vm.lastError == PairingApproval.timeoutMessage)
        #expect(vm.pairingPrompt == nil)
        #expect(!vm.isConnecting)
        #expect(!vm.navigateToDashboard)
        // No stale PAIRING_REQUIRED error outlives the expired wait.
        #expect(gateway.disconnectCount == 1)
    }

    @Test(.timeLimit(.minutes(1)))
    func cancellingWhileWaitingClearsTheCard() async {
        let gateway = FakeGatewayConnectionManager(connectError: Self.pending)
        let profiles = FakeConnectionProfileRepository()
        let vm = makeVM(handoff: PairingHandoff(), gateway: gateway, profiles: profiles, pairingRetryInterval: .seconds(60))
        vm.setHost("192.168.1.20")
        vm.connect()
        while vm.pairingPrompt == nil { await Task.yield() }
        let waiting = vm.connectTask
        vm.cancelConnect()
        #expect(vm.pairingPrompt == nil)
        #expect(!vm.isConnecting)
        #expect(vm.lastError == nil)

        await waiting?.value
        #expect(gateway.disconnectCount == 1)
        #expect(gateway.connectURLs.count == 1)
        #expect(profiles.savedProfiles.isEmpty)
        #expect(!vm.navigateToDashboard)
    }

    @Test func aConnectSupersededByAnotherScreenShowsNoError() async {
        let gateway = FakeGatewayConnectionManager(failFirst: [CancellationError()])
        let profiles = FakeConnectionProfileRepository()
        let vm = makeVM(handoff: PairingHandoff(), gateway: gateway, profiles: profiles)
        vm.setHost("192.168.1.20")

        vm.connect()
        await vm.connectTask?.value

        #expect(vm.lastError == nil)
        #expect(!vm.isConnecting)
        #expect(!vm.navigateToDashboard)
        #expect(profiles.savedProfiles.isEmpty)
    }

    @Test func aKeychainFailureIsShownAndNotRetried() async {
        let storage = GatewayError.deviceCredentialStorage(operation: "read the device identity")
        let gateway = FakeGatewayConnectionManager(failFirst: [Self.pending, storage])
        let vm = makeVM(handoff: PairingHandoff(), gateway: gateway, pairingRetryInterval: .milliseconds(10))
        vm.setHost("192.168.1.20")

        vm.connect()
        await vm.connectTask?.value

        #expect(gateway.connectURLs.count == 2)
        #expect(vm.lastError == storage.errorDescription)
        #expect(vm.pairingPrompt == nil)
        #expect(!vm.isConnecting)
    }

    // MARK: - Route prefill and pairing re-delivery

    @Test func routePrefillAppliesOnlyOnce() throws {
        let url = try #require(URL(string: "http://192.168.1.20:18789"))
        let vm = makeVM(handoff: PairingHandoff())

        vm.applyRoutePrefill(url)
        vm.setToken("tok_typed")
        // Back from a pushed screen: a second appear.
        vm.applyRoutePrefill(url)

        #expect(vm.host == "192.168.1.20")
        #expect(vm.token == "tok_typed")
    }

    @Test func aRedeliveryBeforeTheFirstAppearKeepsItsToken() throws {
        let handoff = PairingHandoff()
        let url = try #require(URL(string: "http://192.168.1.20:18789"))
        let vm = makeVM(handoff: handoff)
        handoff.stash(token: "tok_new", for: url)

        // SwiftUI delivered onChange(of: pairingRedelivery) before onAppear.
        vm.pairingRedelivered(url, routeURL: url)
        vm.applyRoutePrefill(url)

        #expect(vm.token == "tok_new")
    }

    @Test func aRedeliveryForAnotherGatewayIsIgnored() throws {
        let handoff = PairingHandoff()
        let routeURL = try #require(URL(string: "http://192.168.1.20:18789"))
        let otherURL = try #require(URL(string: "http://192.168.1.99:18789"))
        let vm = makeVM(handoff: handoff)
        vm.applyRoutePrefill(routeURL)
        vm.setToken("tok_typed")
        handoff.stash(token: "tok_other", for: otherURL)

        vm.pairingRedelivered(otherURL, routeURL: routeURL)

        #expect(vm.host == "192.168.1.20")
        #expect(vm.token == "tok_typed")
    }

    // MARK: - A hand edit of the address drops the pairing link's name and token

    @Test func editingTheHostByHandDropsTheLinksNameAndToken() async {
        let gateway = FakeGatewayConnectionManager()
        let profiles = FakeConnectionProfileRepository()
        let vm = makeVM(handoff: PairingHandoff(), gateway: gateway, profiles: profiles)
        vm.pastedLink = PairingLinkFixture.link(
            json: #"{"v":1,"url":"http://192.168.1.20:18789","token":"tok_A","name":"Gateway A"}"#
        )
        vm.applyPastedLink()
        #expect(vm.pairedName == "Gateway A")

        vm.setHost("192.168.1.99")

        #expect(vm.pairedName == nil)
        #expect(vm.token == "")
        vm.connect()
        await vm.connectTask?.value
        #expect(gateway.connectTokens == [nil])
        #expect(profiles.savedProfiles.map(\.displayName) == ["Fake Gateway"])
    }

    @Test func editingThePortOrSchemeByHandDropsTheLinksNameAndToken() {
        let link = PairingLinkFixture.link(
            json: #"{"v":1,"url":"http://192.168.1.20:18789","token":"tok_A","name":"Gateway A"}"#
        )
        let vm = makeVM(handoff: PairingHandoff())

        vm.pastedLink = link
        vm.applyPastedLink()
        vm.setPort("18790")
        #expect(vm.pairedName == nil)
        #expect(vm.token == "")

        vm.pastedLink = link
        vm.applyPastedLink()
        #expect(vm.token == "tok_A")
        vm.setUseHttps(true)
        #expect(vm.pairedName == nil)
        #expect(vm.token == "")
    }

    @Test func aTypedTokenSurvivesAHostEdit() {
        let vm = makeVM(handoff: PairingHandoff())
        vm.pastedLink = PairingLinkFixture.link(
            json: #"{"v":1,"url":"http://192.168.1.20:18789","token":"tok_A","name":"Gateway A"}"#
        )
        vm.applyPastedLink()
        vm.setToken("tok_typed")

        vm.setHost("192.168.1.99")

        #expect(vm.token == "tok_typed")
        #expect(vm.pairedName == nil)
    }

    @Test func settingTheSameAddressKeepsThePairing() {
        let vm = makeVM(handoff: PairingHandoff())
        vm.pastedLink = PairingLinkFixture.link(
            json: #"{"v":1,"url":"http://192.168.1.20:18789","token":"tok_A","name":"Gateway A"}"#
        )
        vm.applyPastedLink()

        // A binding can write back the value it already shows.
        vm.setHost("192.168.1.20")
        vm.setPort("18789")
        vm.setUseHttps(false)

        #expect(vm.token == "tok_A")
        #expect(vm.pairedName == "Gateway A")
    }
}
