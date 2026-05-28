import Testing
import Foundation
@testable import GhostCrab

/// Pins the upstream-gateway-tolerant decoding of `/health`.
///
/// The upstream `ghcr.io/openclaw/openclaw:latest` returns `{"status": true}` —
/// a boolean for a field the DTO declares as String. The custom `init(from:)`
/// in ``HealthResponse`` must coerce both shapes without throwing, since the
/// connect-flow uses `client.health()` as its only liveness probe.
///
/// Direct counterpart to the Kotlin regression test in
/// `OpenClawApiClientTest.kt` ("health tolerates boolean status field from
/// upstream gateway"), which protects the same contract on Android.
@Suite("HealthResponse decoding")
struct HealthResponseDecodingTests {

    @Test("Accepts string status")
    func acceptsStringStatus() throws {
        let json = Data(#"{"status":"ok"}"#.utf8)
        let r = try JSONDecoder().decode(HealthResponse.self, from: json)
        #expect(r.status == "ok")
    }

    @Test("Accepts boolean status from upstream OpenClaw gateway")
    func acceptsBooleanStatus() throws {
        let json = Data(#"{"status":true}"#.utf8)
        let r = try JSONDecoder().decode(HealthResponse.self, from: json)
        // Boolean true coerced to a string — exact spelling is implementation
        // choice ("true", "ok", whatever), so just assert non-empty.
        #expect(!r.status.isEmpty)
    }

    @Test("Accepts boolean false")
    func acceptsBooleanFalse() throws {
        let json = Data(#"{"status":false}"#.utf8)
        let r = try JSONDecoder().decode(HealthResponse.self, from: json)
        #expect(!r.status.isEmpty)
    }

    @Test("Tolerates unknown extra fields")
    func ignoresUnknownFields() throws {
        let json = Data(#"{"status":"ok","uptime":42,"extra":{"foo":"bar"}}"#.utf8)
        let r = try JSONDecoder().decode(HealthResponse.self, from: json)
        #expect(r.status == "ok")
    }
}
