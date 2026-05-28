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
    case `protocol`(rpcCode: Int, message: String)
    case verificationFailed(expected: String, actual: String)
    case unknown(cause: String)

    /// Whether retrying the install is likely to help.
    public var isRetryable: Bool {
        switch self {
        case .network, .unknown:
            return true
        case .unauthorized, .notFound, .dependencyConflict, .protocol, .verificationFailed:
            return false
        }
    }
}
