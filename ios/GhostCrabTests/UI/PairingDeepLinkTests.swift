import Testing
import Foundation
@testable import GhostCrab

/// Pins the `onOpenURL` gate: only `ghostcrab:` URLs are handled, the token goes
/// to the hand-off, and the route carries the gateway URL only.
@MainActor
@Suite("PairingDeepLink")
struct PairingDeepLinkTests {

    @Test func validLinkPushesManualEntryWithURLOnlyAndStashesTheToken() throws {
        let handoff = PairingHandoff()
        let link = try #require(URL(string: PairingLinkFixture.link(
            json: #"{"v":1,"url":"http://192.168.1.20:18789","token":"tok_123"}"#
        )))

        let route = try PairingDeepLink.route(for: link, handoff: handoff)

        let gateway = try #require(URL(string: "http://192.168.1.20:18789"))
        #expect(route == Route.manualEntry(prefillURL: gateway))
        #expect(!String(describing: route).contains("tok_123"))
        #expect(handoff.takePairing(for: gateway)?.token == "tok_123")
    }

    @Test func uppercaseSchemeIsHandled() throws {
        let link = try #require(URL(string: PairingLinkFixture.link(json: #"{"v":1,"url":"http://192.168.1.20:18789"}"#)
            .replacingOccurrences(of: "ghostcrab://", with: "GHOSTCRAB://")))
        let route = try PairingDeepLink.route(for: link, handoff: PairingHandoff())
        #expect(route == Route.manualEntry(prefillURL: URL(string: "http://192.168.1.20:18789")))
    }

    @Test func otherSchemesAreIgnored() throws {
        let handoff = PairingHandoff()
        let sentinelURL = try #require(URL(string: "http://192.168.1.99:18789"))
        handoff.stash(token: "tok_sentinel", for: sentinelURL)
        let encoded = PairingLinkFixture.link(json: #"{"v":1,"url":"http://192.168.1.20:18789","token":"tok_123"}"#)
            .replacingOccurrences(of: "ghostcrab://pair", with: "https://gw.example.com/pair")
        let link = try #require(URL(string: encoded))

        #expect(try PairingDeepLink.route(for: link, handoff: handoff) == nil)
        // Untouched sentinel: the ignored link never reached the hand-off.
        #expect(handoff.takePairing(for: sentinelURL)?.token == "tok_sentinel")
    }

    @Test func invalidLinkThrowsAndStashesNothing() throws {
        let handoff = PairingHandoff()
        // Every stash replaces the pending entry, so a sentinel that survives
        // proves the rejected link stashed nothing (and cleared nothing).
        let sentinelURL = try #require(URL(string: "http://192.168.1.99:18789"))
        handoff.stash(token: "tok_sentinel", for: sentinelURL)
        let link = try #require(URL(string: PairingLinkFixture.link(
            json: #"{"v":1,"url":"ftp://192.168.1.20","token":"tok_123"}"#
        )))

        var thrown: PairingLinkError?
        do {
            _ = try PairingDeepLink.route(for: link, handoff: handoff)
        } catch let error as PairingLinkError {
            thrown = error
        }
        #expect(thrown == PairingLinkError.invalidGatewayURL)
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
