import Foundation
import os

/// Privacy-aware logging facade for the GhostCrab iOS app.
///
/// Wraps `os.Logger` (iOS 14+, preferred over `os_log`) and sanitises sensitive
/// substrings — bearer tokens and embedded URL credentials — before they reach
/// the unified logging system.
///
/// Direct port of `app/.../crash/PrivacySafeUncaughtExceptionHandler.kt`.
///
/// **Usage:**
/// ```swift
/// let log = PrivacySafeLogger(category: "api")
/// log.debug("Sending request to \(url)")
/// log.error("Connection failed", error: error)
/// ```
///
/// Sensitive values formatted with `%{private}@` so the underlying os.Logger
/// also redacts them in Console.app outside debug builds — defence in depth.
public struct PrivacySafeLogger: Sendable {

    /// Subsystem all loggers share. Matches the bundle ID.
    public static let subsystem = "com.qavren.ghostcrab"

    private let logger: Logger

    /// - Parameter category: A short module name, e.g. `"api"`, `"discovery"`, `"keychain"`.
    public init(category: String) {
        self.logger = Logger(subsystem: Self.subsystem, category: category)
    }

    // MARK: - Public API

    public func debug(_ message: @autoclosure () -> String) {
        let sanitised = Self.sanitise(message())
        logger.debug("\(sanitised, privacy: .public)")
    }

    public func info(_ message: @autoclosure () -> String) {
        let sanitised = Self.sanitise(message())
        logger.info("\(sanitised, privacy: .public)")
    }

    public func warning(_ message: @autoclosure () -> String) {
        let sanitised = Self.sanitise(message())
        logger.warning("\(sanitised, privacy: .public)")
    }

    public func error(_ message: @autoclosure () -> String, error: (any Error)? = nil) {
        var text = Self.sanitise(message())
        if let error {
            text += " — " + Self.sanitise(String(describing: error))
        }
        logger.error("\(text, privacy: .public)")
    }

    public func fault(_ message: @autoclosure () -> String) {
        let sanitised = Self.sanitise(message())
        logger.fault("\(sanitised, privacy: .public)")
    }

    // MARK: - Sanitisation

    /// Redacts bearer tokens and URL credentials. Internal for unit testing.
    internal static func sanitise(_ text: String) -> String {
        var result = text
        result = bearerRegex.stringByReplacingMatches(
            in: result,
            range: NSRange(result.startIndex..., in: result),
            withTemplate: "Bearer [REDACTED]"
        )
        result = credentialsURLRegex.stringByReplacingMatches(
            in: result,
            range: NSRange(result.startIndex..., in: result),
            withTemplate: "$1[REDACTED]@"
        )
        return result
    }

    // swiftlint:disable force_try
    private static let bearerRegex = try! NSRegularExpression(
        pattern: #"Bearer\s+\S+"#,
        options: .caseInsensitive
    )
    private static let credentialsURLRegex = try! NSRegularExpression(
        pattern: #"(https?://)[^@\s]+@"#,
        options: .caseInsensitive
    )
    // swiftlint:enable force_try
}
