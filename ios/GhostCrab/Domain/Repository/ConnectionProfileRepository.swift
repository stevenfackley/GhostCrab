import Foundation

/// CRUD operations for locally stored gateway connection profiles.
///
/// Profile metadata (id, URL, display name, timestamps) persists in `UserDefaults`
/// (Codable round-trip). Bearer tokens persist in the iOS Keychain with
/// `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, keyed by profile id.
///
/// **Contract frozen at v1.0.**
public protocol ConnectionProfileRepository: Sendable {

    /// Emits the current list of saved profiles, updating on any change.
    ///
    /// - Returns: An `AsyncStream` that always replays the latest list on subscription.
    func observeProfiles() -> AsyncStream<[ConnectionProfile]>

    /// Saves or updates a profile. If `profile.id` already exists, it is overwritten.
    ///
    /// - Parameters:
    ///   - profile: The profile metadata to persist.
    ///   - token: Bearer token to encrypt and store, or `nil` to clear any existing token.
    func saveProfile(_ profile: ConnectionProfile, token: String?) async throws

    /// Retrieves the stored bearer token for a profile.
    ///
    /// - Parameter profileId: The `ConnectionProfile.id` to look up.
    /// - Returns: The bearer token string, or `nil` if none was stored.
    /// - Throws: `GatewayError.profileNeedsReauth` if the Keychain entry exists but
    ///   could not be read back (e.g. after a factory reset). The corrupted entry is
    ///   cleared before throwing.
    func getToken(profileId: String) async throws -> String?

    /// Deletes a profile and its associated stored token.
    ///
    /// No-op if the profile does not exist.
    ///
    /// - Parameter profileId: The `ConnectionProfile.id` to delete.
    func deleteProfile(profileId: String) async throws
}
