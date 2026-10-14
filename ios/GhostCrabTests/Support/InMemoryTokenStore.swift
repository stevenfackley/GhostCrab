import Foundation
import Security
@testable import GhostCrab

/// `TokenStoring` fake. Failures can be switched on mid-test to simulate a
/// Keychain that can no longer read or delete (e.g. after a device restore).
final class InMemoryTokenStore: TokenStoring, @unchecked Sendable {
    private let lock = NSLock()
    private var tokens: [String: String]
    private var failReads = false
    private var failDeletes = false

    init(tokens: [String: String] = [:]) {
        self.tokens = tokens
    }

    func simulateFailures(reads: Bool, deletes: Bool) {
        lock.withLock {
            failReads = reads
            failDeletes = deletes
        }
    }

    /// Reads a token directly, bypassing any simulated failure.
    func token(for profileId: String) -> String? {
        lock.withLock { tokens[profileId] }
    }

    /// Number of stored tokens, bypassing any simulated failure.
    var count: Int { lock.withLock { tokens.count } }

    func setToken(_ token: String, for profileId: String) throws {
        lock.withLock { tokens[profileId] = token }
    }

    func getToken(for profileId: String) throws -> String? {
        let (shouldFail, value) = lock.withLock { (failReads, tokens[profileId]) }
        if shouldFail { throw KeychainError.unexpectedStatus(errSecDecode) }
        return value
    }

    func deleteToken(for profileId: String) throws {
        let shouldFail = lock.withLock { failDeletes }
        if shouldFail { throw KeychainError.unexpectedStatus(errSecInteractionNotAllowed) }
        lock.withLock { tokens[profileId] = nil }
    }

    func deleteAllTokens() throws {
        let shouldFail = lock.withLock { failDeletes }
        if shouldFail { throw KeychainError.unexpectedStatus(errSecInteractionNotAllowed) }
        lock.withLock { tokens.removeAll() }
    }
}
