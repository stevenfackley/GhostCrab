import Testing
import Foundation
@testable import GhostCrab

/// Pins the `ghostcrab://pair` decoder. Mirrors Android's `PairCodeTest`:
/// schema v1 (`{"v":1,"url":…,"token":…,"name":…}`), version read first,
/// a 4096-character cap checked before any decoding, and gateway URLs limited
/// to http(s) with a host, no userinfo and a port in 1–65535.
@Suite("PairingLink decoding")
struct PairingLinkTests {

    // MARK: - Accepted payloads

    @Test func decodesURLTokenAndName() throws {
        let request = try PairingLink.decode(PairingLinkFixture.link(
            json: #"{"v":1,"url":"http://192.168.1.20:18789","token":"tok_123","name":"Basement"}"#
        ))
        #expect(request.gatewayURL.absoluteString == "http://192.168.1.20:18789")
        #expect(request.token == "tok_123")
        #expect(request.displayName == "Basement")
    }

    @Test func missingTokenDecodesWithNilToken() throws {
        let request = try PairingLink.decode(PairingLinkFixture.link(json: #"{"v":1,"url":"https://gw.example.com:8443"}"#))
        #expect(request.gatewayURL.absoluteString == "https://gw.example.com:8443")
        #expect(request.token == nil)
        #expect(request.displayName == nil)
    }

    @Test func blankTokenIsTreatedAsMissing() throws {
        let request = try PairingLink.decode(PairingLinkFixture.link(json: #"{"v":1,"url":"http://10.0.0.2:18789","token":"   "}"#))
        #expect(request.token == nil)
    }

    @Test func unknownFieldsAreIgnored() throws {
        let request = try PairingLink.decode(PairingLinkFixture.link(
            json: #"{"v":1,"url":"http://10.0.0.2:18789","extra":{"nested":true}}"#
        ))
        #expect(request.gatewayURL.absoluteString == "http://10.0.0.2:18789")
    }

    @Test func missingPortGetsTheSchemeDefault() throws {
        let http = try PairingLink.decode(PairingLinkFixture.link(json: #"{"v":1,"url":"http://192.168.1.20"}"#))
        #expect(http.gatewayURL.absoluteString == "http://192.168.1.20:18789")
        let https = try PairingLink.decode(PairingLinkFixture.link(json: #"{"v":1,"url":"https://abc-xyz.trycloudflare.com"}"#))
        #expect(https.gatewayURL.absoluteString == "https://abc-xyz.trycloudflare.com:443")
    }

    @Test func pathQueryAndFragmentAreDropped() throws {
        let request = try PairingLink.decode(PairingLinkFixture.link(
            json: #"{"v":1,"url":"http://192.168.1.20:18789/ui/?x=1#top"}"#
        ))
        #expect(request.gatewayURL.absoluteString == "http://192.168.1.20:18789")
    }

    @Test func schemeAndHostAreCaseInsensitive() throws {
        let link = PairingLinkFixture.link(json: #"{"v":1,"url":"http://192.168.1.20:18789"}"#)
            .replacingOccurrences(of: "ghostcrab://pair", with: "GhostCrab://PAIR")
        #expect(try PairingLink.decode(link).gatewayURL.host == "192.168.1.20")
    }

    // MARK: - Size cap

    @Test func payloadOverTheCapIsRejected() {
        let name = String(repeating: "x", count: PairingLink.maxLength)
        let oversized = PairingLinkFixture.link(json: #"{"v":1,"url":"http://192.168.1.20:18789","name":"\#(name)"}"#)
        #expect(throws: PairingLinkError.payloadTooLarge) {
            try PairingLink.decode(oversized)
        }
    }

    @Test func sizeCapIsCheckedBeforeDecoding() {
        // Invalid base64 would throw `.invalidBase64` if it were decoded first.
        let oversized = "ghostcrab://pair?p=" + String(repeating: "!", count: PairingLink.maxLength)
        #expect(throws: PairingLinkError.payloadTooLarge) {
            try PairingLink.decode(oversized)
        }
    }

    // MARK: - Version

    @Test func unknownVersionIsRejectedExplicitly() {
        #expect(throws: PairingLinkError.unsupportedVersion(2)) {
            try PairingLink.decode(PairingLinkFixture.link(json: #"{"v":2,"url":"http://192.168.1.20:18789"}"#))
        }
    }

    @Test func versionIsReadBeforeTheRestOfThePayload() {
        // A v2 payload without a v1 `url` still reports the version, not the missing URL.
        #expect(throws: PairingLinkError.unsupportedVersion(2)) {
            try PairingLink.decode(PairingLinkFixture.link(json: #"{"v":2}"#))
        }
    }

    @Test func missingVersionIsRejected() {
        #expect(throws: PairingLinkError.invalidJSON) {
            try PairingLink.decode(PairingLinkFixture.link(json: #"{"url":"http://192.168.1.20:18789"}"#))
        }
    }

    @Test func stringVersionIsRejected() {
        // `v` must be a JSON integer; "1" is not coerced.
        #expect(throws: PairingLinkError.invalidJSON) {
            try PairingLink.decode(PairingLinkFixture.link(json: #"{"v":"1","url":"http://192.168.1.20:18789"}"#))
        }
    }

    // MARK: - Malformed payloads

    @Test func badBase64IsRejected() {
        #expect(throws: PairingLinkError.invalidBase64) {
            try PairingLink.decode("ghostcrab://pair?p=!!!!")
        }
    }

    @Test func base64ThatIsNotJSONIsRejected() {
        // "aGVsbG8" is base64url for "hello".
        #expect(throws: PairingLinkError.invalidJSON) {
            try PairingLink.decode("ghostcrab://pair?p=aGVsbG8")
        }
    }

    @Test func missingPayloadIsRejected() {
        #expect(throws: PairingLinkError.missingPayload) {
            try PairingLink.decode("ghostcrab://pair")
        }
    }

    @Test func otherSchemesAreNotPairingLinks() {
        #expect(!PairingLink.isPairingLink("https://example.com/pair?p=abc"))
        #expect(throws: PairingLinkError.notAPairingLink) {
            try PairingLink.decode("https://example.com/pair?p=abc")
        }
    }

    @Test func ghostcrabLinkWithAnotherHostIsPairingDataButRejected() {
        // Every ghostcrab: link is pairing data, so a broken one gets a pairing error.
        #expect(PairingLink.isPairingLink("ghostcrab://other?p=abc"))
        #expect(throws: PairingLinkError.notAPairingLink) {
            try PairingLink.decode("ghostcrab://other?p=abc")
        }
    }

    // MARK: - Gateway URL validation

    @Test func missingGatewayURLIsRejected() {
        #expect(throws: PairingLinkError.invalidGatewayURL) {
            try PairingLink.decode(PairingLinkFixture.link(json: #"{"v":1,"token":"tok_123"}"#))
        }
    }

    @Test(arguments: [
        "ftp://192.168.1.20",
        "javascript:alert(1)",
        "file:///etc/passwd",
        "http://",
        "192.168.1.20:18789",
    ])
    func nonHTTPGatewayURLIsRejected(url: String) {
        #expect(throws: PairingLinkError.invalidGatewayURL) {
            try PairingLink.decode(PairingLinkFixture.link(json: #"{"v":1,"url":"\#(url)"}"#))
        }
    }

    @Test(arguments: [
        "http://user:pw@192.168.1.20:18789",
        "http://user@192.168.1.20:18789",
        "https://:pw@gw.example.com",
    ])
    func userinfoInGatewayURLIsRejected(url: String) {
        #expect(throws: PairingLinkError.invalidGatewayURL) {
            try PairingLink.decode(PairingLinkFixture.link(json: #"{"v":1,"url":"\#(url)"}"#))
        }
    }

    @Test(arguments: ["http://192.168.1.20:0", "http://192.168.1.20:65536", "http://192.168.1.20:99999"])
    func outOfRangePortIsRejected(url: String) {
        #expect(throws: PairingLinkError.invalidGatewayURL) {
            try PairingLink.decode(PairingLinkFixture.link(json: #"{"v":1,"url":"\#(url)"}"#))
        }
    }

    @Test func plainGatewayURLValidatorSharesTheRules() throws {
        let url = try #require(PairingLink.gatewayURL(from: "  https://gw.example.com:18789/path  "))
        #expect(url.absoluteString == "https://gw.example.com:18789")
        #expect(PairingLink.gatewayURL(from: "http://user:pw@gw.example.com") == nil)
        #expect(PairingLink.gatewayURL(from: "ghostcrab://pair?p=abc") == nil)
    }

    // MARK: - Token redaction

    @Test func descriptionsNeverContainTheToken() throws {
        let request = try PairingLink.decode(PairingLinkFixture.link(
            json: #"{"v":1,"url":"http://192.168.1.20:18789","token":"tok_secret_123"}"#
        ))
        var dumped = ""
        dump(request, to: &dumped)
        #expect(!String(describing: request).contains("tok_secret_123"))
        #expect(!String(reflecting: request).contains("tok_secret_123"))
        #expect(!dumped.contains("tok_secret_123"))
        #expect(String(describing: request).contains("192.168.1.20"))
    }
}
