import Testing
import Foundation
@testable import GhostCrab

/// Pins the `ghostcrab://pair` decoder. Mirrors Android's `PairCodeTest`:
/// schema v1 (`{"v":1,"url":…,"token":…,"name":…}`), version read first,
/// a 4096-character cap checked before any decoding, and gateway URLs limited
/// to http(s) with a host, no userinfo and a port in 1–65535.
@Suite("PairingLink decoding")
struct PairingLinkTests {

    // MARK: - openclaw qr setup codes and ws(s) URLs (ADR-0007)

    /// base64url of {"url":"ws://172.17.0.2:18789","bootstrapToken":"bt-test-0001","expiresAtMs":1792028000000}.
    private static let setupCode =
        "eyJ1cmwiOiJ3czovLzE3Mi4xNy4wLjI6MTg3ODkiLCJib290c3RyYXBUb2tlbiI6ImJ0LXRlc3QtMDAwMSIsImV4cGlyZXNBdE1zIjoxNzkyMDI4MDAwMDAwfQ"

    private func encode(_ json: String) -> String {
        Base64URL.encode(Data(json.utf8))
    }

    @Test func aSetupCodeIsRecognisedWithItsGatewayURLAndNothingElse() throws {
        let info = try #require(PairingLink.setupCode(Self.setupCode))
        #expect(info.gatewayURL?.absoluteString == "http://172.17.0.2:18789")
        #expect(!String(reflecting: info).contains("bt-test-0001"))
        var dumped = ""
        dump(info, to: &dumped)
        #expect(!dumped.contains("bt-test-0001"))
    }

    @Test func ocPairLinksAreSetupCodesEvenWhenUndecodable() {
        #expect(PairingLink.setupCode("oc-pair://" + Self.setupCode)?.gatewayURL?.absoluteString == "http://172.17.0.2:18789")
        #expect(PairingLink.setupCode("OC-PAIR://" + Self.setupCode)?.gatewayURL?.absoluteString == "http://172.17.0.2:18789")
        #expect(PairingLink.setupCode("oc-pair://not-base64!") == SetupCodeInfo(gatewayURL: nil))
        #expect(PairingLink.setupCode("oc-pair://") == SetupCodeInfo(gatewayURL: nil))
    }

    @Test func aWssSetupCodeNamesTheHTTPSGatewayOn443() {
        let code = encode(#"{"url":"wss://gw.example/ws","bootstrapToken":"bt"}"#)
        #expect(PairingLink.setupCode(code)?.gatewayURL?.absoluteString == "https://gw.example:443")
    }

    @Test func aSetupCodeWithoutAUsableURLIsStillExplained() throws {
        let info = try #require(PairingLink.setupCode(encode(#"{"bootstrapToken":"bt"}"#)))
        #expect(info.gatewayURL == nil)
        #expect(PairingLink.setupCodeExplanation(info).contains("the gateway URL"))
    }

    @Test func theExplanationSaysWhyAndWhatToDoInstead() throws {
        let text = PairingLink.setupCodeExplanation(try #require(PairingLink.setupCode(Self.setupCode)))
        #expect(text.contains("http://172.17.0.2:18789"))
        #expect(text.contains("gateway token"))
        #expect(text.hasSuffix("\nopenclaw devices approve <requestId>"))
        #expect(!text.contains("`"))
        #expect(!text.contains("bt-test-0001"))
    }

    @Test func otherInputIsNotASetupCode() {
        #expect(PairingLink.setupCode(encode(#"{"url":"ws://h:1"}"#)) == nil)
        #expect(PairingLink.setupCode(encode(#"{"url":"ws://h:1","bootstrapToken":"  "}"#)) == nil)
        #expect(PairingLink.setupCode("http://192.168.1.20:18789") == nil)
        #expect(PairingLink.setupCode("gw.example.com") == nil)
        #expect(PairingLink.setupCode("") == nil)
    }

    @Test func plainWsURLsMapToHTTPBaseURLs() {
        #expect(PairingLink.webSocketGatewayURL(from: "ws://192.168.1.50:18789")?.absoluteString == "http://192.168.1.50:18789")
        #expect(PairingLink.webSocketGatewayURL(from: "wss://gw.example/ws")?.absoluteString == "https://gw.example:443")
        #expect(PairingLink.webSocketGatewayURL(from: "WS://192.168.1.50:18789/ws")?.absoluteString == "http://192.168.1.50:18789")
        #expect(PairingLink.webSocketGatewayURL(from: "ws://admin:pw@192.168.1.50:18789") == nil)
        #expect(PairingLink.webSocketGatewayURL(from: "http://h:1") == nil)
    }

    @Test func wsAndHTTPSpellingsShareOneDeviceTokenKey() throws {
        let url = try #require(PairingLink.webSocketGatewayURL(from: "ws://192.168.1.50:18789/ws"))
        #expect(GatewayKey.of(url.absoluteString) == GatewayKey.of("ws://192.168.1.50:18789"))
    }

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

    @Test(arguments: ["http://[fe80::1%en0]:18789", "http://[fe80::1%25en0]:18789/status"])
    func ipv6ZoneIdIsDropped(raw: String) throws {
        let url = try #require(PairingLink.gatewayURL(from: raw))
        #expect(url.absoluteString == "http://[fe80::1]:18789")
        #expect(url.host?.contains("%") == false)
    }

    @Test func ipv6ZoneIdIsDroppedFromAPairingLink() throws {
        let request = try PairingLink.decode(PairingLinkFixture.link(
            json: #"{"v":1,"url":"http://[fe80::1%en0]:18789","token":"tok_123"}"#
        ))
        #expect(request.gatewayURL.absoluteString == "http://[fe80::1]:18789")
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
