import Foundation

/// All errors originating from gateway communication or local profile state.
///
/// Every case carries an `isRetryable` flag so callers can implement retry logic
/// without inspecting the concrete case.
///
/// Direct port of the Kotlin `sealed class GatewayException` hierarchy.
public enum GatewayError: Error, Sendable {

    /// The gateway host is not reachable (DNS failure, refused connection, network error).
    ///
    /// - Parameters:
    ///   - url: The URL that could not be reached.
    ///   - underlying: Optional underlying error (e.g. `URLError`).
    case unreachable(url: String, underlying: (any Error & Sendable)? = nil)

    /// The gateway rejected the request due to missing or invalid credentials.
    ///
    /// - Parameters:
    ///   - url: The URL that returned the auth error.
    ///   - statusCode: HTTP status code (401 or 403).
    case auth(url: String, statusCode: Int)

    /// The gateway returned an unexpected HTTP error.
    ///
    /// - Parameters:
    ///   - url: The URL that returned the error.
    ///   - statusCode: HTTP status code.
    ///   - body: Response body excerpt, if available.
    case api(url: String, statusCode: Int, body: String? = nil)

    /// The request to the gateway timed out.
    ///
    /// - Parameters:
    ///   - url: The URL that timed out.
    ///   - underlying: Optional underlying error.
    case timeout(url: String, underlying: (any Error & Sendable)? = nil)

    /// TLS handshake failed (e.g. self-signed certificate not trusted).
    ///
    /// - Parameters:
    ///   - url: The URL that failed TLS negotiation.
    ///   - underlying: Optional underlying error.
    case tls(url: String, underlying: (any Error & Sendable)? = nil)

    /// A saved profile's stored token could not be retrieved/decrypted
    /// (e.g. after a factory reset or Keychain reset).
    ///
    /// The corrupted entry has been cleared; the user must re-authenticate.
    ///
    /// - Parameter profileId: The profile whose token is now invalid.
    case profileNeedsReauth(profileId: String)

    /// The gateway does not have the AI recommendation skill installed.
    ///
    /// - Parameter url: The gateway URL.
    case aiServiceUnavailable(url: String)

    /// The AI recommendation service rate-limited the request.
    ///
    /// - Parameter url: The gateway URL.
    case aiQuotaExceeded(url: String)

    /// A config value failed client-side or server-side validation.
    ///
    /// - Parameters:
    ///   - field: The config key that failed validation (e.g. `"gateway.http.port"`).
    ///   - reason: Human-readable reason for the failure.
    case configValidation(field: String, reason: String)

    /// A cleartext (`http://`) request to a public IP literal was refused by
    /// the app's own policy (`CleartextPublicIPGuard`) before it left the
    /// device. Not retryable as-is: the user must switch to HTTPS or turn on
    /// Settings → Security → "Allow HTTP to public IPs" and reconnect.
    ///
    /// Mirrors Kotlin's `CleartextBlockedException`.
    ///
    /// - Parameters:
    ///   - url: The URL that was blocked.
    ///   - host: The public IP literal that triggered the block.
    case cleartextBlocked(url: String, host: String)

    /// A gateway RPC answered `ok:false` with a code GhostCrab has no more specific case for (ADR-0007).
    ///
    /// - Parameters:
    ///   - url: The gateway WebSocket endpoint.
    ///   - method: The RPC method.
    ///   - code: Top-level error code (e.g. `UNAVAILABLE`).
    ///   - detailCode: `error.details.code`, when present.
    ///   - message: The server's message, verbatim.
    ///   - retryable: The server's `retryable` hint; absent on the wire means `false`.
    ///   - details: `error.details` when it is an object (e.g. `config.patch` validation `issues[]`), else `nil`.
    ///   - retryAfterMs: The server's back-off hint, if any.
    case rpc(
        url: String,
        method: String,
        code: String,
        detailCode: String?,
        message: String,
        retryable: Bool,
        details: [String: AnyCodable]? = nil,
        retryAfterMs: Int? = nil
    )

    /// The gateway does not implement `method` (`INVALID_REQUEST unknown method: …`). Retrying cannot help.
    case methodUnsupported(url: String, method: String)

    /// The session lacks the operator scope `method` needs (`FORBIDDEN` / `MISSING_SCOPE`). Approve this device
    /// with that scope on the gateway host.
    case missingScope(url: String, method: String, scope: String)

    /// The device must be approved on the gateway host (`NOT_PAIRED` / `PAIRING_REQUIRED`). Retryable: the same
    /// connect succeeds once `openclaw devices approve <requestId>` ran.
    ///
    /// - Parameters:
    ///   - url: The gateway WebSocket endpoint.
    ///   - requestId: The pending pairing request, when the gateway sent one. Stable across reconnects.
    ///   - reason: `not-paired`, `scope-upgrade`, `role-upgrade` or `metadata-upgrade`.
    ///   - requestedScopes: The scopes this device asked for.
    case pairingRequired(url: String, requestId: String?, reason: String, requestedScopes: [String])

    /// The `connect` handshake's credential or device proof was rejected (`AUTH_*` / `DEVICE_*` detail codes, e.g.
    /// `AUTH_TOKEN_MISMATCH`, `AUTH_TOKEN_MISSING`, `DEVICE_IDENTITY_REQUIRED`).
    ///
    /// - Parameters:
    ///   - url: The gateway WebSocket endpoint.
    ///   - detailCode: The structured code.
    ///   - message: The server's message, verbatim.
    ///   - recommendedNextStep: Upstream recovery hint (`update_auth_credentials`, `wait_then_retry`, …), if any.
    ///   - retryable: The server's `retryable` hint. The rejection is retryable when this is `true` or
    ///     `recommendedNextStep` is `wait_then_retry`; otherwise the credential has to change first.
    ///   - retryAfterMs: The server's back-off hint, if any.
    case connectRejected(
        url: String,
        detailCode: String,
        message: String,
        recommendedNextStep: String?,
        retryable: Bool = false,
        retryAfterMs: Int? = nil
    )

    /// The gateway closed the control-plane socket with a policy-violation close (WebSocket 1008) and no response
    /// explaining it, e.g. a socket that never sent `connect` or a frame the gateway refused. Not retryable as-is:
    /// the same frames would be refused again.
    ///
    /// - Parameters:
    ///   - url: The gateway WebSocket endpoint.
    ///   - closeCode: The WebSocket close code.
    ///   - reason: The close reason the gateway sent; may be empty.
    case connectionClosed(url: String, closeCode: Int, reason: String)

    /// The gateway answered, but not in a shape this client understands (malformed challenge or hello,
    /// unexpected payload). Not a connectivity problem.
    case protocolViolation(url: String, detail: String)

    /// Upstream's `recommendedNextStep` for a rejection that clears by itself after a back-off.
    private static let nextStepWaitThenRetry = "wait_then_retry"

    /// Whether the operation that produced this error is safe to retry.
    public var isRetryable: Bool {
        switch self {
        case .unreachable, .timeout, .pairingRequired:
            return true
        case let .rpc(_, _, _, _, _, retryable, _, _):
            return retryable
        case let .connectRejected(_, _, _, recommendedNextStep, retryable, _):
            return retryable || recommendedNextStep == GatewayError.nextStepWaitThenRetry
        case .auth, .api, .tls, .profileNeedsReauth,
             .aiServiceUnavailable, .aiQuotaExceeded, .configValidation,
             .cleartextBlocked, .methodUnsupported, .missingScope, .connectionClosed, .protocolViolation:
            return false
        }
    }

    /// The cleartext policy text, shared with `CleartextPublicIPGuard`'s own
    /// error so the advice is worded identically everywhere. Names the exact
    /// iOS Settings section and toggle that lift the block.
    ///
    /// Mirrors Kotlin's `CleartextBlockedException.policyMessage`, with the
    /// iOS Settings path ("Security", not Android's "Connections").
    ///
    /// - Parameter host: The public IP literal that was blocked.
    /// - Returns: One sentence naming the host, then the two ways out.
    public static func cleartextPolicyMessage(host: String) -> String {
        "Cleartext HTTP to public IP \(host) blocked by app policy. "
            + "Use HTTPS or enable Settings \u{2192} Security \u{2192} Allow HTTP to public IPs."
    }
}

// MARK: - LocalizedError

extension GatewayError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .unreachable(let url, _):
            return "Gateway unreachable at \(url)"
        case .auth(let url, let statusCode):
            return "Authentication failed at \(url) (HTTP \(statusCode))"
        case .api(let url, let statusCode, let body):
            if let body, !body.isEmpty {
                return "Gateway API error at \(url): HTTP \(statusCode) \u{2014} \(body)"
            }
            return "Gateway API error at \(url): HTTP \(statusCode)"
        case .timeout(let url, _):
            return "Gateway request timed out at \(url)"
        case .tls(let url, _):
            return "TLS handshake failed for \(url)"
        case .profileNeedsReauth(let profileId):
            return "Stored token for profile \(profileId) is no longer valid. Please reconnect."
        case .aiServiceUnavailable(let url):
            return "AI recommendation skill not available on gateway at \(url)"
        case .aiQuotaExceeded(let url):
            return "AI recommendation quota exceeded at \(url)"
        case .configValidation(let field, let reason):
            return "Invalid value for '\(field)': \(reason)"
        case .cleartextBlocked(let url, let host):
            return "\(GatewayError.cleartextPolicyMessage(host: host)) URL: \(url)"
        case let .rpc(url, method, code, detailCode, message, _, _, _):
            let detail = detailCode.map { "/\($0)" } ?? ""
            return "Gateway RPC \(method) failed at \(url): \(code)\(detail) \u{2014} \(message)"
        case let .methodUnsupported(url, method):
            return "Gateway at \(url) does not support '\(method)' (INVALID_REQUEST unknown method)"
        case let .missingScope(url, method, scope):
            return "Gateway at \(url) refused '\(method)': missing scope \(scope) (FORBIDDEN/MISSING_SCOPE)"
        case let .pairingRequired(url, requestId, reason, _):
            let command = requestId.map { "openclaw devices approve \($0)" } ?? "openclaw devices list"
            return "Gateway at \(url) needs this device approved (\(reason)). On the gateway host run: \(command)"
        case let .connectRejected(url, detailCode, message, _, _, _):
            return "Gateway at \(url) rejected the connect handshake: \(detailCode) \u{2014} \(message)"
        case let .connectionClosed(url, closeCode, reason):
            let trimmed = reason.trimmingCharacters(in: .whitespacesAndNewlines)
            let suffix = trimmed.isEmpty ? "" : " \u{2014} \(trimmed)"
            return "Gateway at \(url) closed the connection: WebSocket \(closeCode)\(suffix)"
        case let .protocolViolation(url, detail):
            return "Unexpected gateway response at \(url): \(detail)"
        }
    }
}
