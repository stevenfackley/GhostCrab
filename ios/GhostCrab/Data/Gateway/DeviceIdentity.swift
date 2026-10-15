import CryptoKit
import Foundation

/// Why a device identity could not be built.
public enum DeviceIdentityError: Error, Equatable, Sendable {
    /// The stored seed is not 32 bytes.
    case invalidSeedLength(Int)
}

/// This install's Ed25519 device identity (ADR-0007). `deviceId` is `hex(sha256(raw public key))`, the fingerprint
/// the gateway checks against `device.publicKey`.
///
/// Holds only the 32-byte seed, in storage of its own that is zeroed when the last copy of the identity is released;
/// the CryptoKit key is rebuilt per signature straight from that storage, so no temporary seed copy is made. The seed
/// leaves this type only through ``seedBase64ForStorage()``. `description`, `debugDescription` and the mirror show the
/// device id and nothing else.
public struct DeviceIdentity: Sendable {

    /// Ed25519 seed length.
    public static let seedBytes = 32

    /// The private seed. Sensitive — never log; only ``DeviceIdentityStore`` persists it.
    private let seed: SeedBytes

    /// Raw 32-byte public key.
    public let publicKeyRaw: Data

    /// `hex(sha256(publicKeyRaw))`.
    public let deviceId: String

    /// The raw public key, base64url without padding (`connect.params.device.publicKey`).
    public var publicKeyBase64URL: String { Base64URL.encode(publicKeyRaw) }

    /// - Parameter seed: A 32-byte Ed25519 seed. Copied, never retained: the caller zeroes its own copy.
    /// - Throws: ``DeviceIdentityError/invalidSeedLength(_:)``, or CryptoKit's error for an unusable key.
    public init(seed: Data) throws {
        guard seed.count == Self.seedBytes else { throw DeviceIdentityError.invalidSeedLength(seed.count) }
        let key = try Curve25519.Signing.PrivateKey(rawRepresentation: seed)
        self.init(seed: SeedBytes(copying: seed), publicKeyRaw: key.publicKey.rawRepresentation)
    }

    private init(seed: SeedBytes, publicKeyRaw: Data) {
        self.seed = seed
        self.publicKeyRaw = publicKeyRaw
        self.deviceId = Self.deviceId(forRawPublicKey: publicKeyRaw)
    }

    /// - Returns: A fresh random identity.
    public static func generate() -> DeviceIdentity {
        let key = Curve25519.Signing.PrivateKey()
        var raw = key.rawRepresentation
        defer { raw.resetBytes(in: 0..<raw.count) } // the temporary copy CryptoKit handed out
        return DeviceIdentity(seed: SeedBytes(copying: raw), publicKeyRaw: key.publicKey.rawRepresentation)
    }

    /// - Parameter payload: The v3 device-auth payload.
    /// - Returns: The Ed25519 signature, base64url without padding.
    /// - Throws: CryptoKit's error (never for a valid seed).
    public func sign(_ payload: String) throws -> String {
        let key = try seed.withUnsafeBytes { try Curve25519.Signing.PrivateKey(rawRepresentation: $0) }
        return Base64URL.encode(try key.signature(for: Data(payload.utf8)))
    }

    /// The seed as standard base64, for the Keychain item only. The intermediate byte copy is zeroed before
    /// returning. Sensitive — never log.
    ///
    /// - Returns: The base64 seed.
    func seedBase64ForStorage() -> String {
        var copy = seed.withUnsafeBytes { Data($0) }
        defer { copy.resetBytes(in: 0..<copy.count) }
        return copy.base64EncodedString()
    }

    /// - Returns: `hex(sha256(raw))`.
    public static func deviceId(forRawPublicKey raw: Data) -> String {
        SHA256.hash(data: raw).map { String(format: "%02x", $0) }.joined()
    }
}

extension DeviceIdentity: CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    public var description: String { "DeviceIdentity(deviceId: \(deviceId))" }
    public var debugDescription: String { description }
    public var customMirror: Mirror { Mirror(self, children: ["deviceId": deviceId], displayStyle: .struct) }
}

/// The seed bytes of a ``DeviceIdentity``, zeroed with `memset_s` (which the optimiser may not drop) on release.
///
/// `@unchecked Sendable`: `bytes` is written only in `init` and `deinit`; every other access is a read.
private final class SeedBytes: @unchecked Sendable {

    private var bytes: [UInt8]

    init(copying source: some ContiguousBytes) {
        bytes = source.withUnsafeBytes { Array($0) }
    }

    func withUnsafeBytes<R>(_ body: (UnsafeRawBufferPointer) throws -> R) rethrows -> R {
        try bytes.withUnsafeBytes(body)
    }

    deinit {
        bytes.withUnsafeMutableBytes { buffer in
            guard let base = buffer.baseAddress else { return }
            _ = memset_s(base, buffer.count, 0, buffer.count)
        }
    }
}

/// RFC 4648 §5 base64url without padding, as the gateway uses for keys and signatures.
public enum Base64URL {

    /// - Returns: `data` as base64url, no `=` padding.
    public static func encode(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    /// - Returns: The decoded bytes, or `nil` when `text` is not base64url (padding optional).
    public static func decode(_ text: String) -> Data? {
        var base64 = text.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        let remainder = base64.count % 4
        if remainder > 0 { base64.append(String(repeating: "=", count: 4 - remainder)) }
        return Data(base64Encoded: base64)
    }
}
