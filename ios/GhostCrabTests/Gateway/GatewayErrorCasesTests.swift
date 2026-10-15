import Foundation
import Testing
@testable import GhostCrab

@Suite("GatewayError protocol-4 cases")
struct GatewayErrorCasesTests {

    private let url = "ws://192.168.1.50:18789/ws"

    @Test func pairingRequiredIsRetryableAndNamesTheApproveCommand() {
        let error = GatewayError.pairingRequired(url: url, requestId: "req-1", reason: "not-paired", requestedScopes: ["operator.read"])
        #expect(error.isRetryable)
        #expect(error.errorDescription?.contains("openclaw devices approve req-1") == true)
    }

    @Test func pairingRequiredWithoutARequestIdNamesTheListCommand() {
        let error = GatewayError.pairingRequired(url: url, requestId: nil, reason: "not-paired", requestedScopes: [])
        #expect(error.errorDescription?.contains("openclaw devices list") == true)
    }

    @Test(arguments: ["req;rm -rf ~", "$(id)", "--help", "req 9", String(repeating: "a", count: 129)])
    func pairingRequiredNeverPutsAnUnsafeRequestIdInTheCommand(requestId: String) {
        let error = GatewayError.pairingRequired(url: url, requestId: requestId, reason: "not-paired", requestedScopes: [])
        let message = error.errorDescription ?? ""
        #expect(message.hasSuffix("On the gateway host run: openclaw devices list"))
        #expect(!message.contains(requestId))
        #expect(!message.contains("approve"))
    }

    @Test func connectRejectedNamesUrlAndCode() {
        let error = GatewayError.connectRejected(url: url, detailCode: "AUTH_TOKEN_MISMATCH", message: "token mismatch", recommendedNextStep: nil)
        #expect(!error.isRetryable)
        #expect(error.errorDescription?.contains(url) == true)
        #expect(error.errorDescription?.contains("AUTH_TOKEN_MISMATCH") == true)
    }

    @Test func connectRejectedHonoursWaitThenRetryAndTheRetryableHint() {
        #expect(GatewayError.connectRejected(url: url, detailCode: "AUTH_RATE_LIMITED", message: "too many attempts",
                                             recommendedNextStep: "wait_then_retry", retryAfterMs: 30_000).isRetryable)
        #expect(GatewayError.connectRejected(url: url, detailCode: "AUTH_TOKEN_MISMATCH", message: "unauthorized",
                                             recommendedNextStep: "update_auth_credentials", retryable: true).isRetryable)
        #expect(!GatewayError.connectRejected(url: url, detailCode: "AUTH_TOKEN_MISMATCH", message: "unauthorized",
                                              recommendedNextStep: "update_auth_credentials").isRetryable)
    }

    @Test func rpcHonoursTheRetryableHint() {
        #expect(GatewayError.rpc(url: url, method: "x", code: "UNAVAILABLE", detailCode: nil, message: "busy", retryable: true).isRetryable)
        #expect(!GatewayError.rpc(url: url, method: "x", code: "UNAVAILABLE", detailCode: nil, message: "busy", retryable: false).isRetryable)
    }

    @Test func rpcDescriptionNamesMethodCodesAndMessage() {
        let error = GatewayError.rpc(url: url, method: "config.patch", code: "INVALID_REQUEST", detailCode: "X_CODE",
                                     message: "invalid config", retryable: false)
        #expect(error.errorDescription == "Gateway RPC config.patch failed at \(url): INVALID_REQUEST/X_CODE \u{2014} invalid config")
    }

    @Test func aPolicyCloseIsNotRetryableAndNamesCodeAndReason() {
        let error = GatewayError.connectionClosed(url: url, closeCode: 1008, reason: "invalid request frame")
        #expect(!error.isRetryable)
        #expect(error.errorDescription == "Gateway at \(url) closed the connection: WebSocket 1008 \u{2014} invalid request frame")
        #expect(GatewayError.connectionClosed(url: url, closeCode: 1008, reason: "").errorDescription
            == "Gateway at \(url) closed the connection: WebSocket 1008")
    }

    @Test func unsupportedScopeAndProtocolCasesAreNotRetryable() {
        #expect(!GatewayError.methodUnsupported(url: url, method: "skills.list").isRetryable)
        #expect(!GatewayError.missingScope(url: url, method: "config.get", scope: "operator.admin").isRetryable)
        #expect(!GatewayError.protocolViolation(url: url, detail: "connect answered without a hello-ok payload").isRetryable)
    }

    @Test func installMappingUsesTheNewCases() {
        #expect(GatewayError.missingScope(url: url, method: "skills.install", scope: "operator.admin").toSkillInstallError()
            == .unauthorized(missingScope: "operator.admin"))
        let notFound = GatewayError.rpc(url: url, method: "skills.install", code: "UNAVAILABLE", detailCode: nil,
                                        message: "Skill \"x\" not found on ClawHub.", retryable: false)
        #expect(notFound.toSkillInstallError(slug: "x") == .notFound(slug: "x"))
        #expect(GatewayError.rpc(url: url, method: "skills.install", code: "INVALID_REQUEST", detailCode: nil, message: "bad", retryable: false)
            .toSkillInstallError() == .protocol(code: "INVALID_REQUEST", message: "bad"))
        #expect(GatewayError.methodUnsupported(url: url, method: "skills.install").toSkillInstallError()
            == .protocol(code: "INVALID_REQUEST", message: "unknown method: skills.install"))
        #expect(!GatewayError.connectionClosed(url: url, closeCode: 1008, reason: "bye").toSkillInstallError().isRetryable)
    }

    // Moved from OpenClawAPIClientDecodingTests when the REST skill routes were deleted (Task 20).

    @Test func cleartextBlockedInstallIsNotRetryable() {
        let error = GatewayError.cleartextBlocked(url: "ws://8.8.8.8:18789/ws", host: "8.8.8.8")
        let mapped = error.toSkillInstallError()
        // Its own case, shown as is: no "Protocol error" prefix in the skill UI.
        #expect(mapped == .cleartextBlocked(message: error.errorDescription ?? ""))
        #expect(!mapped.isRetryable)
    }

    /// The HTTP status stands in for the missing RPC code, whatever the status.
    @Test func anHttpStatusInstallErrorIsAProtocolErrorNamingTheStatus() {
        // What setSkillEnabled reports for a key skills.status does not list.
        let body = "skill weather is not listed by skills.status"
        let mapped = GatewayError.api(url: "ws://192.168.1.50:18789/ws", statusCode: 404, body: body).toSkillInstallError()
        #expect(mapped == .protocol(code: "HTTP 404", message: body))
        #expect(!mapped.isRetryable)
        #expect(GatewayError.api(url: "http://192.168.1.50:18789", statusCode: 0, body: "Malformed URL").toSkillInstallError()
            == .protocol(code: "HTTP 0", message: "Malformed URL"))
        #expect(GatewayError.api(url: "http://192.168.1.50:18789/health", statusCode: 404).toSkillInstallError()
            == .protocol(code: "HTTP 404", message: ""))
    }
}
