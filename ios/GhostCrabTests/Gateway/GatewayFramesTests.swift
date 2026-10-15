import Foundation
import Testing
@testable import GhostCrab

@Suite("Gateway frames (protocol 4)")
struct GatewayFramesTests {

    @Test func challengeDecodesAsAnEventWithNonceAndTs() throws {
        guard case .event(let event)? = GatewayFrameCodec.decode(try GatewayFixtures.text("challenge")) else {
            Issue.record("not an event frame")
            return
        }
        #expect(event.event == "connect.challenge")
        let payload = try #require(event.payload?.objectValue)
        #expect(payload["nonce"]?.value as? String == "71d63897-7ebd-44e8-a27a-20ac1598b846")
        #expect(payload["ts"]?.value as? Int == 1_792_027_507_354)
    }

    @Test func helloDecodesAsAnOkResponseWithAStringId() throws {
        guard case .response(let response)? = GatewayFrameCodec.decode(try GatewayFixtures.text("hello-ok-paired")) else {
            Issue.record("not a response frame")
            return
        }
        #expect(response.id == "qa-1")
        #expect(response.ok)
        #expect(response.payload?.objectValue?["type"]?.value as? String == "hello-ok")
        #expect(response.error == nil)
    }

    @Test func errorResponseExposesCodeAndDetails() throws {
        guard case .response(let response)? = GatewayFrameCodec.decode(try GatewayFixtures.text("err-pairing-required")) else {
            Issue.record("not a response frame")
            return
        }
        #expect(!response.ok)
        #expect(response.error?.code == "NOT_PAIRED")
        #expect(response.error?.detailCode == "PAIRING_REQUIRED")
        #expect(response.error?.detailString("requestId") == "af0b5d81-7bd2-4b86-80b0-4a489b541974")
        #expect(response.error?.detailStrings("requestedScopes") == TestKeys.operatorScopes)
        #expect(response.error?.retryable == nil)
        #expect(response.error?.retryAfterMs == nil)
    }

    @Test func aFailedResponseKeepsBothPayloadAndError() throws {
        guard case .response(let response)? = GatewayFrameCodec.decode(try GatewayFixtures.text("err-skill-not-found")) else {
            Issue.record("not a response frame")
            return
        }
        #expect(!response.ok)
        #expect(response.error?.code == "UNAVAILABLE")
        #expect(response.payload?.objectValue?["ok"]?.value as? Bool == false)
    }

    @Test func eventsCarryTheirSequenceNumber() throws {
        guard case .event(let event)? = GatewayFrameCodec.decode(try GatewayFixtures.text("evt-config-changed")) else {
            Issue.record("not an event frame")
            return
        }
        #expect(event.event == "config.changed")
        #expect(event.seq == 2)
    }

    @Test(arguments: [
        "not json",
        "[1,2,3]",
        #"{"jsonrpc":"2.0","id":1,"result":{}}"#,
        #"{"type":"res","id":7,"ok":true}"#,
        #"{"type":"res","id":"x"}"#,
        #"{"type":"res","id":"x","ok":"true"}"#,
        #"{"type":"res","id":"x","ok":1}"#,
        #"{"type":"event"}"#,
        #"{"type":"event","event":7}"#,
    ])
    func nonProtocolInputDecodesToNil(text: String) {
        let ignored = GatewayFrameCodec.decode(text) == nil
        #expect(ignored, "decoded \(text)")
    }

    @Test func badOptionalErrorFieldsDoNotDowngradeAPairingError() throws {
        let text = #"{"type":"res","id":"x","ok":false,"error":{"code":"NOT_PAIRED","message":"pairing required","#
            + #""retryable":"yes","retryAfterMs":"soon","details":{"code":"PAIRING_REQUIRED","requestId":"r-1"}}}"#
        guard case .response(let response)? = GatewayFrameCodec.decode(text) else {
            Issue.record("not a response frame")
            return
        }
        let error = try #require(response.error)
        #expect(error.code == "NOT_PAIRED")
        #expect(error.detailCode == "PAIRING_REQUIRED")
        #expect(error.detailString("requestId") == "r-1")
        #expect(error.retryable == nil)
        #expect(error.retryAfterMs == nil)
    }

    @Test func aStringBooleanIsNotARetryHint() throws {
        let text = #"{"type":"res","id":"x","ok":false,"error":{"code":"UNAVAILABLE","message":"m","retryable":"true"}}"#
        guard case .response(let response)? = GatewayFrameCodec.decode(text) else {
            Issue.record("not a response frame")
            return
        }
        #expect(response.error?.retryable == nil)
    }

    @Test func wellTypedRetryHintsAreRead() throws {
        let text = #"{"type":"res","id":"x","ok":false,"error":{"code":"UNAVAILABLE","message":"starting","retryable":true,"retryAfterMs":2000}}"#
        guard case .response(let response)? = GatewayFrameCodec.decode(text) else {
            Issue.record("not a response frame")
            return
        }
        #expect(response.error?.retryable == true)
        #expect(response.error?.retryAfterMs == 2000)
    }

    @Test func anErrorWithoutAStringCodeIsMalformedAndDoesNotEchoTheRawObject() throws {
        let text = #"{"type":"res","id":"x","ok":false,"error":{"code":7,"message":"m","details":{"secret":"s3cr3t"}}}"#
        guard case .response(let response)? = GatewayFrameCodec.decode(text) else {
            Issue.record("not a response frame")
            return
        }
        let error = try #require(response.error)
        #expect(error.code == GatewayFrameCodec.malformedErrorCode)
        #expect(!error.message.contains("s3cr3t"))
        #expect(error.details == nil)
    }

    @Test func requestEncodesTypeReqWithAStringId() throws {
        let object = try jsonObject(try GatewayFrameCodec.encodeRequest(id: "gc-1", method: "health", params: EmptyParams()))
        #expect(object["type"] as? String == "req")
        #expect(object["id"] as? String == "gc-1")
        #expect(object["method"] as? String == "health")
        #expect((object["params"] as? [String: Any])?.isEmpty == true)
    }

    @Test func payloadsReDecodeIntoTypedValues() throws {
        struct Health: Decodable { let ok: Bool; let defaultAgentId: String }
        guard case .response(let response)? = GatewayFrameCodec.decode(try GatewayFixtures.text("res-health")) else {
            Issue.record("not a response frame")
            return
        }
        let health = try #require(response.payload).decoded(as: Health.self)
        #expect(health.ok)
        #expect(health.defaultAgentId == "main")
    }

    @Test func objectAtWalksNestedObjectsAndStopsAtNonObjects() throws {
        let value = try JSONDecoder().decode(AnyCodable.self, from: Data(#"{"a":{"b":{"c":1}},"x":2}"#.utf8))
        #expect(value.object(at: "a", "b")?["c"]?.value as? Int == 1)
        #expect(value.object(at: "x", "y") == nil)
        #expect(value.object(at: "missing") == nil)
    }

    @Test func framesNeverPrintTokens() throws {
        struct Auth: Encodable, Sendable { let token: String }
        struct ConnectParams: Encodable, Sendable { let auth: Auth }
        let connect = GatewayRequestFrame(id: "gc-1", method: "connect", params: ConnectParams(auth: Auth(token: "shared-token")))
        for text in renderings(of: connect) {
            #expect(!text.contains("shared-token"), "\(text)")
        }
        #expect(String(describing: connect).contains("connect"))

        let hello = try #require(GatewayFrameCodec.decode(try GatewayFixtures.text("hello-ok-paired")))
        guard case .response(let response) = hello else {
            Issue.record("not a response frame")
            return
        }
        for text in renderings(of: hello) + renderings(of: response) {
            #expect(!text.contains("dt-test-0001"), "\(text)")
        }
        #expect(String(describing: response).contains("qa-1"))

        let event = GatewayEventFrame(event: "test.event", payload: AnyCodable(["deviceToken": AnyCodable("dt-test-0001")]), seq: 1)
        for text in renderings(of: event) + renderings(of: GatewayFrame.event(event)) {
            #expect(!text.contains("dt-test-0001"), "\(text)")
        }
    }

    /// Every way a value reaches a log: `description`, `debugDescription`, interpolation and the mirror (`dump`).
    private func renderings(of value: Any) -> [String] {
        var dumped = ""
        dump(value, to: &dumped)
        return [String(describing: value), String(reflecting: value), "\(value)", dumped]
    }
}
