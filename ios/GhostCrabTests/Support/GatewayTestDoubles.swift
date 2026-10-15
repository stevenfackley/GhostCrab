import Foundation
import Testing
@testable import GhostCrab

/// Always returns the RFC 8032 TEST 1 identity; `regenerated` is what the test says.
struct FixedIdentityProvider: DeviceIdentityProviding {
    var wasRegenerated = false

    func identity() async throws -> DeviceIdentity { try DeviceIdentity(seed: TestKeys.rfcSeed) }

    var regenerated: Bool {
        get async { wasRegenerated }
    }
}

/// Map-backed ``DeviceTokenStoring``. `failSaves` makes every `save` throw, like a Keychain that refuses writes.
final class InMemoryDeviceTokenStore: DeviceTokenStoring, @unchecked Sendable {

    struct SaveRefused: Error {}

    private let lock = NSLock()
    private var tokens: [String: StoredDeviceToken]
    private let failSaves: Bool

    init(_ tokens: [String: StoredDeviceToken] = [:], failSaves: Bool = false) {
        self.tokens = tokens
        self.failSaves = failSaves
    }

    func load(gatewayKey: String) -> StoredDeviceToken? { lock.withLock { tokens[gatewayKey] } }

    func save(_ token: StoredDeviceToken, gatewayKey: String) throws {
        if failSaves { throw SaveRefused() }
        lock.withLock { tokens[gatewayKey] = token }
    }

    func clear(gatewayKey: String) { lock.withLock { tokens[gatewayKey] = nil } }
}
