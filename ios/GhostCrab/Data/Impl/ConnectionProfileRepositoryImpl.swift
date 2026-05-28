import Foundation

/// ``ProfileStore``-backed implementation of ``ConnectionProfileRepository``.
///
/// Direct port of `ConnectionProfileRepositoryImpl.kt`. The wrapped
/// ``ProfileStore`` actor is the concurrency boundary; this class holds no
/// mutable state of its own, so it is a stateless `final class` marked
/// `Sendable`.
///
/// Unlike the Kotlin version the Swift ``ProfileStore`` already operates on
/// domain ``ConnectionProfile`` values directly (no separate `StoredProfile`
/// DTO), so the `toDomain` / `toStored` mapping helpers from the Kotlin file
/// are unnecessary here — calls forward straight through.
public final class ConnectionProfileRepositoryImpl: ConnectionProfileRepository, Sendable {

    private let store: ProfileStore

    /// - Parameter store: Backing profile + token store.
    public init(store: ProfileStore) {
        self.store = store
    }

    /// Emits the current list of saved profiles, updating on any change.
    ///
    /// - Returns: An `AsyncStream` that replays the latest list on subscription.
    public func observeProfiles() -> AsyncStream<[ConnectionProfile]> {
        store.observeAll()
    }

    /// Saves or updates a profile and its associated bearer token.
    ///
    /// - Parameters:
    ///   - profile: The profile metadata to persist.
    ///   - token: Bearer token to store in the Keychain, or `nil` to clear it.
    /// - Throws: ``KeychainError`` if the underlying token write fails.
    public func saveProfile(_ profile: ConnectionProfile, token: String?) async throws {
        try await store.save(profile, token: token)
    }

    /// Retrieves the stored bearer token for a profile.
    ///
    /// - Parameter profileId: The ``ConnectionProfile/id`` (as `UUID.uuidString`)
    ///   to look up.
    /// - Returns: The bearer token, or `nil` if none was stored.
    /// - Throws: ``GatewayError/profileNeedsReauth(profileId:)`` if the Keychain
    ///   entry exists but cannot be read back (e.g. after a factory reset). The
    ///   corrupted entry is cleared before throwing.
    public func getToken(profileId: String) async throws -> String? {
        do {
            return try store.getToken(for: profileId)
        } catch {
            // Best-effort cleanup of a corrupted entry, mirroring the Kotlin
            // `ProfileNeedsReauthException` heal-then-throw flow. Ignore any
            // delete failure — we're already in an error path.
            try? await store.delete(id: profileId)
            throw GatewayError.profileNeedsReauth(profileId: profileId)
        }
    }

    /// Deletes a profile and its associated stored token.
    ///
    /// - Parameter profileId: The ``ConnectionProfile/id`` (as `UUID.uuidString`)
    ///   to delete. No-op if no profile matches.
    /// - Throws: ``KeychainError`` if the Keychain delete fails for a reason
    ///   other than "not found".
    public func deleteProfile(profileId: String) async throws {
        try await store.delete(id: profileId)
    }
}
