import Testing
import Foundation
@testable import GhostCrab

/// Pins the `onOpenURL` gate: only `ghostcrab:` URLs are handled, the token goes
/// to the hand-off, the stack gets the gateway URL only, and a repeated
/// delivery neither stacks a second Manual Entry nor re-stashes the token.
@MainActor
@Suite("PairingDeepLink")
struct PairingDeepLinkTests {

    private var gateway: URL {
        get throws { try #require(URL(string: "http://192.168.1.20:18789")) }
    }

    private func tokenLink() throws -> URL {
        try #require(URL(string: PairingLinkFixture.link(
            json: #"{"v":1,"url":"http://192.168.1.20:18789","token":"tok_123"}"#
        )))
    }

    @Test func validLinkShowsManualEntryWithURLOnlyAndStashesTheToken() throws {
        let handoff = PairingHandoff()
        let navigator = Navigator(root: .connectionPicker)

        let handled = try PairingDeepLink.open(try tokenLink(), handoff: handoff, navigator: navigator)

        #expect(handled)
        #expect(navigator.path == [Route.manualEntry(prefillURL: try gateway)])
        #expect(!String(describing: navigator.path).contains("tok_123"))
        #expect(handoff.takePairing(for: try gateway)?.token == "tok_123")
    }

    @Test func uppercaseSchemeIsHandled() throws {
        let link = try #require(URL(string: PairingLinkFixture.link(json: #"{"v":1,"url":"http://192.168.1.20:18789"}"#)
            .replacingOccurrences(of: "ghostcrab://", with: "GHOSTCRAB://")))
        let navigator = Navigator(root: .connectionPicker)

        try PairingDeepLink.open(link, handoff: PairingHandoff(), navigator: navigator)

        #expect(navigator.path == [Route.manualEntry(prefillURL: try gateway)])
    }

    @Test func repeatedDeliveryShowsOneManualEntryAndHandsTheTokenOffOnce() throws {
        let handoff = PairingHandoff()
        let navigator = Navigator(root: .connectionPicker)
        let link = try tokenLink()

        // The OS can hand the same link to onOpenURL twice.
        try PairingDeepLink.open(link, handoff: handoff, navigator: navigator)
        try PairingDeepLink.open(link, handoff: handoff, navigator: navigator)
        #expect(navigator.path == [Route.manualEntry(prefillURL: try gateway)])

        // The screen takes the token when it appears...
        #expect(handoff.takePairing(for: try gateway)?.token == "tok_123")
        // ...and a late repeat leaves nothing behind for a later visit.
        try PairingDeepLink.open(link, handoff: handoff, navigator: navigator)
        #expect(navigator.path == [Route.manualEntry(prefillURL: try gateway)])
        #expect(handoff.takePairing(for: try gateway) == nil)
    }

    @Test func otherSchemesAreIgnored() throws {
        let handoff = PairingHandoff()
        let navigator = Navigator(root: .connectionPicker)
        let sentinelURL = try #require(URL(string: "http://192.168.1.99:18789"))
        handoff.stash(token: "tok_sentinel", for: sentinelURL)
        let encoded = PairingLinkFixture.link(json: #"{"v":1,"url":"http://192.168.1.20:18789","token":"tok_123"}"#)
            .replacingOccurrences(of: "ghostcrab://pair", with: "https://gw.example.com/pair")
        let link = try #require(URL(string: encoded))

        #expect(try PairingDeepLink.open(link, handoff: handoff, navigator: navigator) == false)
        #expect(navigator.path.isEmpty)
        // Untouched sentinel: the ignored link never reached the hand-off.
        #expect(handoff.takePairing(for: sentinelURL)?.token == "tok_sentinel")
    }

    @Test func invalidLinkThrowsAndChangesNothing() throws {
        let handoff = PairingHandoff()
        let navigator = Navigator(root: .connectionPicker)
        // Every stash replaces the pending entry, so a sentinel that survives
        // proves the rejected link stashed nothing (and cleared nothing).
        let sentinelURL = try #require(URL(string: "http://192.168.1.99:18789"))
        handoff.stash(token: "tok_sentinel", for: sentinelURL)
        let link = try #require(URL(string: PairingLinkFixture.link(
            json: #"{"v":1,"url":"ftp://192.168.1.20","token":"tok_123"}"#
        )))

        var thrown: PairingLinkError?
        do {
            try PairingDeepLink.open(link, handoff: handoff, navigator: navigator)
        } catch let error as PairingLinkError {
            thrown = error
        }
        #expect(thrown == PairingLinkError.invalidGatewayURL)
        #expect(navigator.path.isEmpty)
        #expect(handoff.takePairing(for: sentinelURL)?.token == "tok_sentinel")
    }

    @Test func rejectionMessageNeverEchoesThePayload() throws {
        let link = try #require(URL(string: PairingLinkFixture.link(
            json: #"{"v":2,"url":"http://192.168.1.20:18789","token":"tok_123"}"#
        )))
        let payload = try #require(URLComponents(url: link, resolvingAgainstBaseURL: false)?
            .queryItems?.first(where: { $0.name == "p" })?.value)

        let message = PairingDeepLink.rejectionMessage(for: link, error: PairingLinkError.unsupportedVersion(2))

        #expect(message == "ghostcrab://pair \u{2014} unsupported pairing data version 2")
        #expect(!message.contains(payload))
        #expect(!message.contains("tok_123"))
    }
}
