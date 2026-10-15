import Foundation
import Security
import Testing
@testable import GhostCrab

/// ``TokenStoring`` whose first `failingReads` reads throw `status`, then behave; its first `failingWrites` stores
/// throw; every store and delete is refused while ``refusingWrites`` is `true`.
private final class FlakyTokenStore: TokenStoring, @unchecked Sendable {
    private let lock = NSLock()
    private var tokens: [String: String]
    private var failingReads: Int
    private var failingWrites: Int
    private let status: OSStatus
    private var refusesWrites: Bool
    private var deletes = 0

    init(tokens: [String: String], failingReads: Int, status: OSStatus, refusingWrites: Bool = false, failingWrites: Int = 0) {
        self.tokens = tokens
        self.failingReads = failingReads
        self.status = status
        self.refusesWrites = refusingWrites
        self.failingWrites = failingWrites
    }

    var stored: [String: String] { lock.withLock { tokens } }
    var deleteCount: Int { lock.withLock { deletes } }

    var refusingWrites: Bool {
        get { lock.withLock { refusesWrites } }
        set { lock.withLock { refusesWrites = newValue } }
    }

    func setToken(_ token: String, for profileId: String) throws {
        let refused: Bool = lock.withLock {
            guard !refusesWrites else { return true }
            if failingWrites > 0 {
                failingWrites -= 1
                return true
            }
            tokens[profileId] = token
            return false
        }
        if refused { throw KeychainError.unexpectedStatus(errSecNotAvailable) }
    }

    func getToken(for profileId: String) throws -> String? {
        let fail: Bool = lock.withLock {
            guard failingReads > 0 else { return false }
            failingReads -= 1
            return true
        }
        if fail { throw KeychainError.unexpectedStatus(status) }
        return lock.withLock { tokens[profileId] }
    }

    func deleteToken(for profileId: String) throws {
        let refused: Bool = lock.withLock {
            guard !refusesWrites else { return true }
            deletes += 1
            tokens[profileId] = nil
            return false
        }
        if refused { throw KeychainError.unexpectedStatus(errSecNotAvailable) }
    }
    func deleteAllTokens() throws { lock.withLock { tokens.removeAll() } }
}

@Suite("Device identity and token stores")
struct DeviceStoresTests {

    private let token = StoredDeviceToken(token: "dt-test-0001", scopes: TestKeys.operatorScopes, role: "operator", deviceId: "device-1")

    // MARK: identity

    @Test func identityIsCreatedOncePersistedAndReloaded() async throws {
        let secrets = InMemoryTokenStore()
        let store = DeviceIdentityStore(secrets: secrets, retryDelay: .zero)
        let first = try await store.identity()
        #expect(secrets.token(for: DeviceIdentityStore.account) != nil)
        #expect(try await store.identity().deviceId == first.deviceId)
        #expect(await store.regenerated == false) // a first install is not a replacement
        #expect(try await DeviceIdentityStore(secrets: secrets, retryDelay: .zero).identity().deviceId == first.deviceId)
    }

    @Test func concurrentFirstUseCreatesOneIdentity() async throws {
        let store = DeviceIdentityStore(secrets: InMemoryTokenStore(), retryDelay: .zero)
        async let first = store.identity()
        async let second = store.identity()
        let ids = try await [first.deviceId, second.deviceId]
        #expect(ids[0] == ids[1])
    }

    @Test func anUnusableSeedIsReplacedAndReported() async throws {
        let secrets = InMemoryTokenStore(tokens: [DeviceIdentityStore.account: Data(count: 5).base64EncodedString()])
        let store = DeviceIdentityStore(secrets: secrets, retryDelay: .zero)
        let identity = try await store.identity()
        let storedText = try #require(secrets.token(for: DeviceIdentityStore.account))
        let stored = try #require(Data(base64Encoded: storedText))
        #expect(stored.count == 32)
        #expect(identity.deviceId.count == 64)
        #expect(await store.regenerated)
    }

    @Test func anUndecodableSeedItemIsReplacedAfterOneRetry() async throws {
        let original = DeviceIdentity.generate()
        let secrets = FlakyTokenStore(tokens: [DeviceIdentityStore.account: original.seedBase64ForStorage()],
                                      failingReads: 2, status: errSecDecode)
        let store = DeviceIdentityStore(secrets: secrets, retryDelay: .zero)
        #expect(try await store.identity().deviceId != original.deviceId)
        #expect(await store.regenerated)
    }

    @Test func aTransientReadFailureIsRetriedAndKeepsTheIdentity() async throws {
        let original = DeviceIdentity.generate()
        let secrets = FlakyTokenStore(tokens: [DeviceIdentityStore.account: original.seedBase64ForStorage()],
                                      failingReads: 1, status: errSecInteractionNotAllowed)
        let store = DeviceIdentityStore(secrets: secrets, retryDelay: .zero)
        #expect(try await store.identity().deviceId == original.deviceId)
        #expect(await store.regenerated == false)
    }

    @Test func aLockedKeychainThrowsAndNeverReplacesTheSeed() async throws {
        let original = DeviceIdentity.generate()
        let seed = original.seedBase64ForStorage()
        let secrets = FlakyTokenStore(tokens: [DeviceIdentityStore.account: seed], failingReads: 2, status: errSecInteractionNotAllowed)
        let store = DeviceIdentityStore(secrets: secrets, retryDelay: .zero)
        guard case .deviceCredentialStorage? = await gatewayError({ _ = try await store.identity() }) else {
            Issue.record("expected deviceCredentialStorage")
            return
        }
        #expect(secrets.stored[DeviceIdentityStore.account] == seed)
        #expect(await store.regenerated == false)
        // The device was unlocked: the next call reads the same identity.
        #expect(try await store.identity().deviceId == original.deviceId)
    }

    @Test func anUnavailableKeychainThrowsAndKeepsTheSeed() async throws {
        let original = DeviceIdentity.generate()
        let seed = original.seedBase64ForStorage()
        let secrets = FlakyTokenStore(tokens: [DeviceIdentityStore.account: seed], failingReads: 2, status: errSecNotAvailable)
        let store = DeviceIdentityStore(secrets: secrets, retryDelay: .zero)
        guard case .deviceCredentialStorage(let operation, _)? = await gatewayError({ _ = try await store.identity() }) else {
            Issue.record("expected deviceCredentialStorage")
            return
        }
        #expect(operation == "read the device identity")
        #expect(secrets.stored[DeviceIdentityStore.account] == seed)
        #expect(await store.regenerated == false)
        // The Keychain recovered: the next call reads the same identity.
        #expect(try await store.identity().deviceId == original.deviceId)
    }

    @Test func regeneratedIsReportedOnlyOnceTheNewSeedIsStored() async throws {
        let secrets = FlakyTokenStore(tokens: [DeviceIdentityStore.account: Data(count: 5).base64EncodedString()],
                                      failingReads: 0, status: errSecSuccess, refusingWrites: true)
        let store = DeviceIdentityStore(secrets: secrets, retryDelay: .zero)
        guard case .deviceCredentialStorage(let operation, _)? = await gatewayError({ _ = try await store.identity() }) else {
            Issue.record("expected deviceCredentialStorage")
            return
        }
        #expect(operation == "store the device identity")
        #expect(await store.regenerated == false)
        // Storage recovered: the replacement lands now and is still reported.
        secrets.refusingWrites = false
        let identity = try await store.identity()
        #expect(secrets.stored[DeviceIdentityStore.account] == identity.seedBase64ForStorage())
        #expect(await store.regenerated)
    }

    @Test func aFailedStoreKeepsTheUnusableItemSoARelaunchStillReportsTheReplacement() async throws {
        let unusable = Data(count: 5).base64EncodedString()
        let secrets = FlakyTokenStore(tokens: [DeviceIdentityStore.account: unusable],
                                      failingReads: 0, status: errSecSuccess, refusingWrites: true)
        let beforeTheCrash = DeviceIdentityStore(secrets: secrets, retryDelay: .zero)
        guard case .deviceCredentialStorage? = await gatewayError({ _ = try await beforeTheCrash.identity() }) else {
            Issue.record("expected deviceCredentialStorage")
            return
        }
        // Never deleted ahead of the store: the next launch finds the same unusable item.
        #expect(secrets.stored[DeviceIdentityStore.account] == unusable)

        secrets.refusingWrites = false
        let relaunched = DeviceIdentityStore(secrets: secrets, retryDelay: .zero)
        let identity = try await relaunched.identity()
        #expect(secrets.stored[DeviceIdentityStore.account] == identity.seedBase64ForStorage())
        #expect(await relaunched.regenerated) // a missing item would read as a first install: no warning
    }

    /// An item the Keychain cannot decode may refuse `SecItemUpdate` as well; left in place it would fail every store,
    /// a lockout no relaunch fixes.
    @Test func anUnusableItemThatRefusesTheStoreIsDeletedAndTheStoreRetriedOnce() async throws {
        let secrets = FlakyTokenStore(tokens: [DeviceIdentityStore.account: Data(count: 5).base64EncodedString()],
                                      failingReads: 0, status: errSecSuccess, failingWrites: 1)
        let store = DeviceIdentityStore(secrets: secrets, retryDelay: .zero)
        let identity = try await store.identity()
        #expect(secrets.deleteCount == 1)
        #expect(secrets.stored[DeviceIdentityStore.account] == identity.seedBase64ForStorage())
        #expect(await store.regenerated)
    }

    @Test func aFirstIdentityThatCannotBeStoredDeletesNothing() async throws {
        let secrets = FlakyTokenStore(tokens: [:], failingReads: 0, status: errSecSuccess, failingWrites: 1)
        let store = DeviceIdentityStore(secrets: secrets, retryDelay: .zero)
        guard case .deviceCredentialStorage(let operation, _)? = await gatewayError({ _ = try await store.identity() }) else {
            Issue.record("expected deviceCredentialStorage")
            return
        }
        #expect(operation == "store the device identity")
        #expect(secrets.deleteCount == 0)
        #expect(await store.regenerated == false)
    }

    @Test func aNewIdentityThatCannotBeStoredIsAStorageError() async throws {
        let store = DeviceIdentityStore(secrets: RefusingWritesTokenStore(), retryDelay: .zero)
        guard case .deviceCredentialStorage(let operation, _)? = await gatewayError({ _ = try await store.identity() }) else {
            Issue.record("expected deviceCredentialStorage")
            return
        }
        #expect(operation == "store the device identity")
        #expect(GatewayError.deviceCredentialStorage(operation: operation).isRetryable)
    }

    // MARK: device tokens

    @Test func deviceTokensRoundTripPerGateway() throws {
        let store = DeviceTokenStore(tokens: InMemoryTokenStore())
        try store.save(token, gatewayKey: "http://gw.local:18789")
        #expect(store.load(gatewayKey: "http://gw.local:18789") == token)
        #expect(store.load(gatewayKey: "http://other:18789") == nil)
        store.clear(gatewayKey: "http://gw.local:18789")
        #expect(store.load(gatewayKey: "http://gw.local:18789") == nil)
    }

    @Test func anUndecodableDeviceTokenReadsAsNilAndIsRemoved() {
        let tokens = InMemoryTokenStore(tokens: [DeviceTokenStore.account(for: "a"): "{not json"])
        #expect(DeviceTokenStore(tokens: tokens).load(gatewayKey: "a") == nil)
        #expect(tokens.token(for: DeviceTokenStore.account(for: "a")) == nil)
    }

    @Test func anUnreadableDeviceTokenReadsAsNilButIsKept() throws {
        let tokens = InMemoryTokenStore()
        let store = DeviceTokenStore(tokens: tokens)
        try store.save(token, gatewayKey: "a")
        tokens.simulateFailures(reads: true, deletes: false)
        #expect(store.load(gatewayKey: "a") == nil)
        #expect(tokens.token(for: DeviceTokenStore.account(for: "a")) != nil)
    }

    @Test func clearAllSavedGatewaysAlsoWipesDeviceTokens() async throws {
        let tokens = InMemoryTokenStore()
        let store = DeviceTokenStore(tokens: tokens)
        try store.save(token, gatewayKey: "a")
        let defaults = try #require(UserDefaults(suiteName: "device-stores-\(UUID().uuidString)"))
        try await ProfileStore(defaults: defaults, keychain: tokens).clearAll()
        #expect(store.load(gatewayKey: "a") == nil)
    }

    @Test func theSeedLivesOutsideTheTokenService() {
        #expect(DeviceIdentityStore.service != Keychain.service)
    }

    @Test func gatewayKeysAreTheNormalisedBaseURL() {
        #expect(GatewayKey.of("http://192.168.1.50:18789/") == "http://192.168.1.50:18789")
        #expect(GatewayKey.of("http://gw.local") == "http://gw.local:18789")
        #expect(GatewayKey.of("HTTP://GW.Local:18789/ws") == "http://gw.local:18789")
        #expect(GatewayKey.of(" garbage/ ") == "garbage")
    }

    @Test func webSocketSpellingsShareTheHttpKey() {
        // Android's `gatewayKeyOf` via `GatewayUrl.fromWebSocketUrl`: scheme-insensitive, host lowercased, path ignored.
        #expect(GatewayKey.of("ws://gw.local:18789/ws") == "http://gw.local:18789")
        #expect(GatewayKey.of("WS://GW.Local/ws") == GatewayKey.of("http://gw.local:18789"))
        #expect(GatewayKey.of("wss://gw.example/ws") == GatewayKey.of("https://gw.example"))
        #expect(GatewayKey.of(" WSS://GW.Example:8443/ws?x=1 ") == "https://gw.example:8443")
        #expect(GatewayKey.of("ws://[fe80::1%en0]:18789/ws") == GatewayKey.of("http://[fe80::1]:18789"))
        #expect(GatewayKey.of("ws://user@gw.local/ws") != GatewayKey.of("http://gw.local:18789")) // userinfo is refused
    }

    @Test func descriptionRedactsTheToken() {
        var dumped = ""
        dump(token, to: &dumped)
        for rendered in [String(describing: token), String(reflecting: token), dumped] {
            #expect(!rendered.contains("dt-test-0001"))
        }
    }
}

/// Reads find nothing; every write is refused.
private final class RefusingWritesTokenStore: TokenStoring {
    func setToken(_ token: String, for profileId: String) throws { throw KeychainError.unexpectedStatus(errSecNotAvailable) }
    func getToken(for profileId: String) throws -> String? { nil }
    func deleteToken(for profileId: String) throws {}
    func deleteAllTokens() throws {}
}
