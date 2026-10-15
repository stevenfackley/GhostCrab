import Foundation
import Security

/// Supplies this install's device identity.
public protocol DeviceIdentityProviding: Sendable {
    /// - Returns: The identity, created and persisted on first use.
    /// - Throws: ``GatewayError/deviceCredentialStorage(operation:underlying:)`` when the Keychain cannot be read
    ///   (e.g. the device is locked) or a new identity cannot be stored.
    func identity() async throws -> DeviceIdentity

    /// `true` once the stored identity was replaced because it could not be read (a corrupt or unusable seed): every
    /// gateway this device was paired with then shows a new pairing request, which the UI should explain. A first
    /// install is not a replacement. Defaults to `false`.
    var regenerated: Bool { get async }
}

extension DeviceIdentityProviding {
    public var regenerated: Bool {
        get async { false }
    }
}

/// Persists the Ed25519 seed (base64) as a Keychain generic password under its own service
/// (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, no iCloud sync), separate from the token service so
/// "Clear all saved gateways" keeps the identity and the gateways' pairing records stay valid (ADR-0007).
///
/// Recovery, as on Android (`DeviceIdentityStore.kt`): a failed Keychain read is retried once after
/// ``readRetryDelay`` (Keychain errors are often transient). Only a seed that is provably unusable (an item the
/// Keychain cannot decode, `errSecDecode`; bad base64; wrong length) is removed and a new identity generated; once the
/// new seed is stored ``regenerated`` turns `true` and a type-name-only warning is logged. That is the only recovery a
/// lost seed allows: every gateway then shows a new pairing request for this device. A read that still fails for any
/// other reason (locked device `errSecInteractionNotAllowed`, `errSecNotAvailable`, `errSecAuthFailed`, a missing
/// entitlement) throws: the seed may well be intact, so replacing it would orphan every pairing for a temporary
/// condition.
///
/// Concurrent first-use calls share one load, so they can never generate two identities. Temporary seed copies are
/// zeroed after use (the base64 text the Keychain API exchanges is a Swift `String` and cannot be).
public actor DeviceIdentityStore: DeviceIdentityProviding {

    /// Keychain service of the seed item.
    public static let service = "com.qavren.ghostcrab.device-identity"

    /// Keychain account of the seed item.
    static let account = "ed25519-seed-v1"

    /// Pause before the single retry of a failed Keychain read.
    public static let readRetryDelay: Duration = .milliseconds(250)

    private static let log = PrivacySafeLogger(category: "device-identity")

    private let secrets: any TokenStoring
    private let retryDelay: Duration
    private var cached: DeviceIdentity?
    private var loading: Task<DeviceIdentity, any Error>?
    /// An unusable seed was found; survives a failed store so the replacement that finally lands is still reported.
    private var replacingUnusableSeed = false

    public private(set) var regenerated = false

    /// - Parameters:
    ///   - secrets: Storage; production uses a ``Keychain`` on ``service``.
    ///   - retryDelay: Pause before retrying a failed read; tests pass zero.
    public init(
        secrets: any TokenStoring = Keychain(service: DeviceIdentityStore.service),
        retryDelay: Duration = DeviceIdentityStore.readRetryDelay
    ) {
        self.secrets = secrets
        self.retryDelay = retryDelay
    }

    public func identity() async throws -> DeviceIdentity {
        if let cached { return cached }
        if let loading { return try await loading.value }
        let load = Task { try await self.loadOrCreate() }
        loading = load
        defer { loading = nil }
        let identity = try await load.value
        cached = identity
        return identity
    }

    /// What the Keychain holds for the seed.
    private enum StoredSeed {
        case absent
        case found(Data)
        /// Unusable; the reason names a type or a length, never a value.
        case unusable(reason: String)
    }

    private func loadOrCreate() async throws -> DeviceIdentity {
        switch try await readSeed() {
        case .absent:
            break // first use: not a replacement
        case .found(var seed):
            defer { seed.resetBytes(in: 0..<seed.count) }
            if let identity = try? DeviceIdentity(seed: seed) { return identity }
            replaceUnusableSeed(reason: "seed of \(seed.count) bytes")
        case .unusable(let reason):
            replaceUnusableSeed(reason: reason)
        }
        let fresh = DeviceIdentity.generate()
        do {
            try secrets.setToken(fresh.seedBase64ForStorage(), for: Self.account)
        } catch {
            throw GatewayError.deviceCredentialStorage(operation: "store the device identity", underlying: Self.sendable(error))
        }
        if replacingUnusableSeed { regenerated = true }
        return fresh
    }

    /// One read, plus one retry after ``readRetryDelay`` when it throws.
    ///
    /// - Throws: ``GatewayError/deviceCredentialStorage(operation:underlying:)`` when both reads fail for any reason
    ///   other than an undecodable item (`errSecDecode`).
    private func readSeed() async throws -> StoredSeed {
        var failure: (any Error)?
        for attempt in 0..<2 {
            if attempt > 0 { try? await Task.sleep(for: retryDelay) }
            do {
                guard let stored = try secrets.getToken(for: Self.account) else { return .absent }
                guard let seed = Data(base64Encoded: stored) else { return .unusable(reason: "seed is not base64") }
                return .found(seed)
            } catch {
                failure = error
            }
        }
        if let keychainError = failure as? KeychainError, keychainError == .unexpectedStatus(errSecDecode) {
            return .unusable(reason: "KeychainError errSecDecode")
        }
        throw GatewayError.deviceCredentialStorage(operation: "read the device identity", underlying: Self.sendable(failure))
    }

    /// Removes the unusable item (best effort: ``Keychain/setToken(_:for:)`` overwrites it anyway) and records why.
    /// ``regenerated`` turns `true` only once the new seed is stored.
    private func replaceUnusableSeed(reason: String) {
        replacingUnusableSeed = true
        Self.log.warning("Device identity unreadable (\(reason)); generating a new one. Gateways will ask to pair again.")
        try? secrets.deleteToken(for: Self.account)
    }

    /// Keychain errors are `Sendable` (`KeychainError`); anything else is dropped rather than cast unchecked.
    private static func sendable(_ error: (any Error)?) -> (any Error & Sendable)? {
        error as? KeychainError
    }
}
