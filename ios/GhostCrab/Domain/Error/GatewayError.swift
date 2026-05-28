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

    /// Whether the operation that produced this error is safe to retry.
    public var isRetryable: Bool {
        switch self {
        case .unreachable, .timeout:
            return true
        case .auth, .api, .tls, .profileNeedsReauth,
             .aiServiceUnavailable, .aiQuotaExceeded, .configValidation:
            return false
        }
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
        }
    }
}
