import Foundation
import Security

/// Errors raised by ``Keychain`` when the underlying `SecItem*` APIs fail.
public enum KeychainError: Error, Equatable, Sendable {
    /// No matching Keychain item was found for the requested account.
    case itemNotFound
    /// `SecItem*` returned an unexpected `OSStatus`. The raw status is preserved
    /// for diagnostics; callers should treat this as a fatal storage error.
    case unexpectedStatus(OSStatus)
}

/// Thread-safe synchronous wrapper around the iOS Keychain for bearer token
/// storage.
///
/// Direct port of the `EncryptedSharedPreferences` token storage from
/// `ConnectionProfileStore.kt`. Each token is stored as a
/// `kSecClassGenericPassword` item under service
/// `"com.qavren.ghostcrab.token"`, with the profile UUID string as the account.
///
/// All items use `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` —
/// equivalent privacy posture to Android's `AES256_GCM` master key:
/// - no iCloud Keychain sync (`ThisDeviceOnly`)
/// - inaccessible while the device is locked at first boot
///
/// `SecItem*` calls are themselves thread-safe, so this type is a stateless
/// `final class` marked `Sendable`.
public final class Keychain: Sendable {

    /// Shared service identifier for every token written by this app.
    public static let service: String = "com.qavren.ghostcrab.token"

    public init() {}

    /// Stores `token` for `profileId`, replacing any existing value.
    ///
    /// - Parameters:
    ///   - token: The bearer token to encrypt and store.
    ///   - profileId: The `ConnectionProfile.id` string used as the Keychain account.
    /// - Throws: ``KeychainError/unexpectedStatus(_:)`` if the add/update fails.
    public func setToken(_ token: String, for profileId: String) throws {
        guard let data = token.data(using: .utf8) else {
            throw KeychainError.unexpectedStatus(errSecParam)
        }

        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.service,
            kSecAttrAccount as String: profileId,
        ]

        // Try update first; fall back to add if no item exists.
        let updateAttrs: [String: Any] = [kSecValueData as String: data]
        let updateStatus = SecItemUpdate(query as CFDictionary, updateAttrs as CFDictionary)

        switch updateStatus {
        case errSecSuccess:
            return
        case errSecItemNotFound:
            var addQuery = query
            addQuery[kSecValueData as String] = data
            addQuery[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            let addStatus = SecItemAdd(addQuery as CFDictionary, nil)
            guard addStatus == errSecSuccess else {
                throw KeychainError.unexpectedStatus(addStatus)
            }
        default:
            throw KeychainError.unexpectedStatus(updateStatus)
        }
    }

    /// Retrieves the token for `profileId`.
    ///
    /// - Parameter profileId: The profile id used as the Keychain account.
    /// - Returns: The stored token, or `nil` if no item exists.
    /// - Throws: ``KeychainError/unexpectedStatus(_:)`` if the read fails for any
    ///   reason other than "not found", including a corrupt value that cannot
    ///   be decoded as UTF-8.
    public func getToken(for profileId: String) throws -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.service,
            kSecAttrAccount as String: profileId,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)

        switch status {
        case errSecSuccess:
            guard let data = item as? Data,
                  let token = String(data: data, encoding: .utf8) else {
                throw KeychainError.unexpectedStatus(errSecDecode)
            }
            return token
        case errSecItemNotFound:
            return nil
        default:
            throw KeychainError.unexpectedStatus(status)
        }
    }

    /// Deletes the token for `profileId`. No-op if no item exists.
    ///
    /// - Parameter profileId: The profile id used as the Keychain account.
    /// - Throws: ``KeychainError/unexpectedStatus(_:)`` if the delete fails for a
    ///   reason other than "not found".
    public func deleteToken(for profileId: String) throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.service,
            kSecAttrAccount as String: profileId,
        ]
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw KeychainError.unexpectedStatus(status)
        }
    }

    /// Removes every token written by this app (service-scoped wipe).
    ///
    /// Intended for "reset all data" / sign-out-everywhere flows.
    ///
    /// - Throws: ``KeychainError/unexpectedStatus(_:)`` if the delete fails for a
    ///   reason other than "not found".
    public func deleteAllTokens() throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.service,
        ]
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw KeychainError.unexpectedStatus(status)
        }
    }
}
