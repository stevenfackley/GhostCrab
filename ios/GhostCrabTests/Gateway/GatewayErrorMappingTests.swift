import Foundation
import Testing
@testable import GhostCrab

@Suite("Gateway error mapping")
struct GatewayErrorMappingTests {

    private let url = "ws://192.168.1.50:18789/ws"

    private func shape(_ fixture: String) throws -> GatewayErrorShape {
        guard case .response(let response)? = GatewayFrameCodec.decode(try GatewayFixtures.text(fixture)) else {
            throw GatewayFixtures.Missing(name: fixture)
        }
        return try #require(response.error)
    }

    @Test func pairingRequiredCarriesRequestIdReasonAndScopes() throws {
        guard case let .pairingRequired(_, requestId, reason, scopes) = try shape("err-pairing-required").toGatewayError(url: url, method: "connect") else {
            Issue.record("expected pairingRequired")
            return
        }
        #expect(requestId == "af0b5d81-7bd2-4b86-80b0-4a489b541974")
        #expect(reason == "not-paired")
        #expect(scopes == TestKeys.operatorScopes)
    }

    @Test func aMetadataUpgradeRePairingCarriesItsOwnRequestIdAndReason() throws {
        let error = try shape("err-pairing-metadata-upgrade").toGatewayError(url: url, method: "connect")
        guard case let .pairingRequired(_, requestId, reason, scopes) = error else {
            Issue.record("expected pairingRequired, got \(error)")
            return
        }
        #expect(requestId == "6837c6b0-f559-445b-bc0c-a50fbe5b5998")
        #expect(reason == "metadata-upgrade")
        #expect(scopes == TestKeys.operatorScopes)
        #expect(error.isRetryable)
    }

    @Test(arguments: [
        ("err-token-mismatch", "AUTH_TOKEN_MISMATCH"),
        ("err-token-missing", "AUTH_TOKEN_MISSING"),
        ("err-device-identity-required", "DEVICE_IDENTITY_REQUIRED"),
    ])
    func credentialFailuresAreConnectRejected(fixture: String, expected: String) throws {
        let error = try shape(fixture).toGatewayError(url: url, method: "connect")
        guard case let .connectRejected(_, detailCode, _, _, _, _) = error else {
            Issue.record("expected connectRejected for \(fixture)")
            return
        }
        #expect(detailCode == expected)
        #expect(!error.isRetryable)
    }

    @Test func tokenMismatchKeepsTheRecommendedNextStep() throws {
        guard case let .connectRejected(_, _, message, nextStep, _, _) = try shape("err-token-mismatch").toGatewayError(url: url, method: "connect") else {
            Issue.record("expected connectRejected")
            return
        }
        #expect(message.hasPrefix("unauthorized: gateway token mismatch"))
        #expect(nextStep == "update_auth_credentials")
    }

    @Test func aConnectRejectionHonoursWaitThenRetryAndTheRetryHints() {
        // Synthetic: no captured frame yet for a rate-limited connect.
        let waited = GatewayErrorShape(
            code: "UNAVAILABLE",
            message: "too many attempts",
            details: AnyCodable(["code": AnyCodable("AUTH_RATE_LIMITED"), "recommendedNextStep": AnyCodable("wait_then_retry")]),
            retryAfterMs: 30_000
        ).toGatewayError(url: url, method: "connect")
        guard case let .connectRejected(_, _, _, _, _, retryAfterMs) = waited else {
            Issue.record("expected connectRejected")
            return
        }
        #expect(waited.isRetryable)
        #expect(retryAfterMs == 30_000)

        let hinted = GatewayErrorShape(
            code: "INVALID_REQUEST",
            message: "unauthorized",
            details: AnyCodable(["code": AnyCodable("AUTH_TOKEN_MISMATCH")]),
            retryable: true
        ).toGatewayError(url: url, method: "connect")
        #expect(hinted.isRetryable)
    }

    @Test func missingScopeNamesTheScope() throws {
        guard case let .missingScope(_, method, scope) = try shape("err-missing-scope").toGatewayError(url: url, method: "status") else {
            Issue.record("expected missingScope")
            return
        }
        #expect(method == "status")
        #expect(scope == "operator.read")
    }

    @Test func legacyMissingScopeMessageStillMaps() {
        guard case let .missingScope(_, _, scope) = GatewayErrorShape(code: "FORBIDDEN", message: "missing scope: operator.admin")
            .toGatewayError(url: url, method: "config.get") else {
            Issue.record("expected missingScope")
            return
        }
        #expect(scope == "operator.admin")
    }

    @Test func unknownMethodIsUnsupported() throws {
        guard case let .methodUnsupported(_, method) = try shape("err-unknown-method").toGatewayError(url: url, method: "skills.list") else {
            Issue.record("expected methodUnsupported")
            return
        }
        #expect(method == "skills.list")
    }

    @Test func anythingElseIsAGenericRPCError() throws {
        guard case let .rpc(_, _, code, detailCode, message, retryable, _, _) = try shape("err-skill-not-found").toGatewayError(url: url, method: "skills.install") else {
            Issue.record("expected rpc")
            return
        }
        #expect(code == "UNAVAILABLE")
        #expect(detailCode == nil)
        #expect(message.hasPrefix("Skill \"qa-nonexistent-skill-zz9\" not found on ClawHub"))
        #expect(!retryable)
    }

    @Test func aClientIdOutsideTheAllowListIsANonRetryableRPCError() throws {
        let error = try shape("err-client-id-invalid").toGatewayError(url: url, method: "connect")
        guard case let .rpc(_, method, code, detailCode, message, _, _, _) = error else {
            Issue.record("expected rpc, got \(error)")
            return
        }
        #expect(method == "connect")
        #expect(code == "INVALID_REQUEST")
        #expect(detailCode == nil)
        #expect(message.contains("/client/id"))
        #expect(!error.isRetryable)
    }

    @Test func aGenericRPCErrorKeepsTheStructuredDetails() throws {
        guard case let .rpc(_, _, _, _, _, _, details, _) = try shape("err-config-invalid").toGatewayError(url: url, method: "config.patch") else {
            Issue.record("expected rpc")
            return
        }
        let issues = try #require(details?["issues"]?.value as? [AnyCodable])
        #expect(issues.count == 1)
        #expect(issues.first?.objectValue?["path"]?.value as? String == "gateway.port")
    }

    @Test func aGenericRPCErrorCarriesTheRetryHints() {
        let error = GatewayErrorShape(code: "UNAVAILABLE", message: "starting", retryable: true, retryAfterMs: 2_000)
            .toGatewayError(url: url, method: "health")
        guard case let .rpc(_, _, _, _, _, retryable, _, retryAfterMs) = error else {
            Issue.record("expected rpc")
            return
        }
        #expect(retryable)
        #expect(error.isRetryable)
        #expect(retryAfterMs == 2_000)
    }

    @Test func aMalformedErrorIsANonRetryableRPCError() throws {
        let text = #"{"type":"res","id":"x","ok":false,"error":{"code":7,"message":"m"}}"#
        guard case .response(let response)? = GatewayFrameCodec.decode(text) else {
            Issue.record("not a response frame")
            return
        }
        let error = try #require(response.error).toGatewayError(url: url, method: "health")
        guard case let .rpc(_, _, code, _, _, _, _, _) = error else {
            Issue.record("expected rpc")
            return
        }
        #expect(code == GatewayFrameCodec.malformedErrorCode)
        #expect(!error.isRetryable)
    }
}
