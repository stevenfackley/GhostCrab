import Testing
import Foundation
@testable import GhostCrab

/// Hand-advanced clock for ``PairingHandoff`` expiry tests.
@MainActor
final class ManualClock {
    private(set) var now = ContinuousClock().now

    func advance(by duration: Duration) {
        now = now.advanced(by: duration)
    }
}

@MainActor
@Suite("PairingHandoff")
struct PairingHandoffTests {

    @Test func takeReturnsTokenOnceForSameOrigin() throws {
        let handoff = PairingHandoff()
        let url = try #require(URL(string: "http://192.168.1.20:18789"))
        handoff.stash(token: "tok_123", for: url)
        #expect(handoff.take(for: url) == "tok_123")
        #expect(handoff.take(for: url) == nil)
    }

    @Test func hostComparisonIgnoresCase() throws {
        let handoff = PairingHandoff()
        handoff.stash(token: "tok_123", for: try #require(URL(string: "http://Gateway.LOCAL:18789")))
        #expect(handoff.take(for: try #require(URL(string: "http://gateway.local:18789"))) == "tok_123")
    }

    @Test func mismatchedOriginGetsNothingAndBurnsTheStash() throws {
        let handoff = PairingHandoff()
        let paired = try #require(URL(string: "http://192.168.1.20:18789"))
        handoff.stash(token: "tok_123", for: paired)
        #expect(handoff.take(for: try #require(URL(string: "http://192.168.1.99:18789"))) == nil)
        #expect(handoff.take(for: paired) == nil)
    }

    @Test func schemeAndPortArePartOfTheOrigin() throws {
        let handoff = PairingHandoff()
        let paired = try #require(URL(string: "http://192.168.1.20:18789"))
        handoff.stash(token: "tok_123", for: paired)
        #expect(handoff.take(for: try #require(URL(string: "https://192.168.1.20:18789"))) == nil)

        handoff.stash(token: "tok_123", for: paired)
        #expect(handoff.take(for: try #require(URL(string: "http://192.168.1.20:18790"))) == nil)
    }

    @Test func laterStashReplacesTheEarlierOne() throws {
        let handoff = PairingHandoff()
        let url = try #require(URL(string: "http://192.168.1.20:18789"))
        handoff.stash(token: "tok_old", for: url)
        handoff.stash(token: "tok_new", for: url)
        #expect(handoff.take(for: url) == "tok_new")
    }

    @Test func tokenWithinTheTTLIsReturned() throws {
        let clock = ManualClock()
        let handoff = PairingHandoff(ttl: .seconds(120), now: { clock.now })
        let url = try #require(URL(string: "http://192.168.1.20:18789"))
        handoff.stash(token: "tok_123", for: url)
        clock.advance(by: .seconds(120) - .milliseconds(1))
        #expect(handoff.take(for: url) == "tok_123")
    }

    @Test func tokenOlderThanTheTTLIsDroppedOnRead() throws {
        let clock = ManualClock()
        let handoff = PairingHandoff(ttl: .seconds(120), now: { clock.now })
        let url = try #require(URL(string: "http://192.168.1.20:18789"))
        handoff.stash(token: "tok_123", for: url)
        clock.advance(by: .seconds(120) + .milliseconds(1))
        #expect(handoff.take(for: url) == nil)
        #expect(handoff.take(for: url) == nil)
    }

    @Test func defaultTTLIsTwoMinutes() {
        #expect(PairingHandoff.defaultTTL == .seconds(120))
    }

    @Test func descriptionsNeverContainTheToken() throws {
        let handoff = PairingHandoff()
        handoff.stash(token: "tok_secret_123", for: try #require(URL(string: "http://192.168.1.20:18789")))
        var dumped = ""
        dump(handoff, to: &dumped)
        #expect(!String(describing: handoff).contains("tok_secret_123"))
        #expect(!String(reflecting: handoff).contains("tok_secret_123"))
        #expect(!dumped.contains("tok_secret_123"))
    }
}
