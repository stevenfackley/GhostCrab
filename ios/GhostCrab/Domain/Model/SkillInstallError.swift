import Foundation

/// Errors surfaced from the in-app skill install pipeline.
///
/// Each case carries an `isRetryable` flag indicating whether a retry has a
/// reasonable chance of succeeding without user intervention.
public enum SkillInstallError: Error, Sendable, Hashable {
    case unauthorized(missingScope: String)
    case notFound(slug: String)
    case dependencyConflict(conflicts: [String])
    case network(cause: String)
    /// The gateway rejected the install with string `code` (e.g. `UNAVAILABLE`) and `message` (ADR-0007).
    case `protocol`(code: String, message: String)
    case verificationFailed(expected: String, actual: String)
    /// The app's cleartext policy refused plain HTTP to a public IP before the
    /// request left the device. `message` is the full user-facing text, with
    /// the fix (HTTPS, or the Settings toggle). Not retryable as-is.
    case cleartextBlocked(message: String)
    case unknown(cause: String)

    /// Whether retrying the install is likely to help.
    public var isRetryable: Bool {
        switch self {
        case .network, .unknown:
            return true
        case .unauthorized, .notFound, .dependencyConflict, .protocol, .verificationFailed, .cleartextBlocked:
            return false
        }
    }
}
