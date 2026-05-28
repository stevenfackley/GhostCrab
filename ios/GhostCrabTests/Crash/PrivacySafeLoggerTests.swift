import Testing
@testable import GhostCrab

/// Guards the regex sanitisation in ``PrivacySafeLogger.sanitise(_:)``.
///
/// If either redaction regex is weakened, tokens or URL credentials would leak
/// into `os_log` output and (in debug builds) Console.app. These tests are the
/// canary.
@Suite("PrivacySafeLogger sanitisation")
struct PrivacySafeLoggerTests {

    // MARK: - Bearer token redaction

    @Test func redactsBearerToken() {
        let input = "GET /status HTTP/1.1, Authorization: Bearer abc123XYZ.token-here"
        let result = PrivacySafeLogger.sanitise(input)
        #expect(!result.contains("abc123XYZ"))
        #expect(result.contains("Bearer [REDACTED]"))
    }

    @Test func redactsBearerCaseInsensitive() {
        let result = PrivacySafeLogger.sanitise("authorization: bearer LONGSECRET")
        #expect(!result.contains("LONGSECRET"))
        #expect(result.lowercased().contains("bearer [redacted]"))
    }

    @Test func redactsMultipleBearersInSameLine() {
        let result = PrivacySafeLogger.sanitise("Bearer one Bearer two")
        let occurrences = result.components(separatedBy: "[REDACTED]").count - 1
        #expect(occurrences == 2)
    }

    // MARK: - URL credentials redaction

    @Test func redactsBasicAuthCredentialsInURL() {
        let input = "GatewayUnreachableException at http://admin:password123@192.168.1.50:18789/health"
        let result = PrivacySafeLogger.sanitise(input)
        #expect(!result.contains("admin"))
        #expect(!result.contains("password123"))
        #expect(result.contains("[REDACTED]@"))
        #expect(result.contains("192.168.1.50:18789"))
    }

    @Test func redactsHTTPSCredentials() {
        let result = PrivacySafeLogger.sanitise("https://user:secret@example.com/")
        #expect(result == "https://[REDACTED]@example.com/")
    }

    @Test func leavesCleanURLAlone() {
        let input = "http://192.168.0.239:3000/health"
        let result = PrivacySafeLogger.sanitise(input)
        #expect(result == input)
    }

    // MARK: - Edge cases

    @Test func leavesPlainTextUntouched() {
        let input = "Connection refused after 30s"
        #expect(PrivacySafeLogger.sanitise(input) == input)
    }

    @Test func handlesEmptyString() {
        #expect(PrivacySafeLogger.sanitise("") == "")
    }
}
