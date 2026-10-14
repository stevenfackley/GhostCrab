import Foundation

/// Errors raised by ``ProfileStore`` writes.
public enum ProfileStoreError: Error, Equatable, Sendable {

    /// The write that ran into an unreadable list. Picks the advice in
    /// ``errorDescription``.
    public enum Operation: Sendable, Equatable {
        /// ``ProfileStore/save(_:token:)``.
        case save
        /// ``ProfileStore/delete(id:)``.
        case delete
    }

    /// The stored profile blob could not be decoded. It was copied to
    /// `backupKey` and removed from the live key, so a retry starts clean
    /// without destroying the unreadable data.
    ///
    /// - Parameters:
    ///   - backupKey: `UserDefaults` key now holding the unreadable blob.
    ///   - operation: The write that was refused.
    case corruptProfileData(backupKey: String, operation: Operation)
}

extension ProfileStoreError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .corruptProfileData(let backupKey, .save):
            return "Saved gateway list was unreadable and has been backed up (\(backupKey)). Try again to save this gateway."
        case .corruptProfileData(let backupKey, .delete):
            return "Saved gateway list was unreadable and has been backed up (\(backupKey)). Nothing was deleted; the list now starts empty."
        }
    }
}

/// Persists ``ConnectionProfile`` metadata in `UserDefaults` and delegates
/// bearer-token storage to ``Keychain``.
///
/// Direct port of `ConnectionProfileStore.kt`:
/// - Profile metadata (Codable round-trip) → `UserDefaults`
/// - Bearer tokens → Keychain (`com.qavren.ghostcrab.token` service)
///
/// The two stores are strictly separated; tokens never appear in
/// `UserDefaults` and metadata never appears in the Keychain.
///
/// Writes never discard a profile list they could not decode: the first
/// write after corruption copies the blob to a backup key, clears the live
/// key and throws ``ProfileStoreError/corruptProfileData(backupKey:operation:)``,
/// so a retry succeeds and the old data stays recoverable. Only the latest
/// backup is kept: quarantining a newer unreadable list prunes older backups
/// (Android keeps a single replaceable backup key), so they cannot pile up.
/// ``clearAll()`` removes the remaining one.
///
/// All public methods are safe to call from any task. Mutations post a
/// `ProfileStoreChanged` notification on `NotificationCenter.default`, which
/// drives ``observeAll()``.
public actor ProfileStore {

    /// `UserDefaults` key for the serialized profile array.
    public static let profilesKey: String = "com.qavren.ghostcrab.profiles"

    /// Notification posted (synchronously, on the calling task) whenever the
    /// stored profile list changes via this store.
    public static let changedNotification: Notification.Name =
        Notification.Name("ProfileStoreChanged")

    /// Prefix of the UserDefaults key that holds a quarantined, unreadable
    /// profile blob (`<prefix><unix-ms>`).
    public static let corruptBackupKeyPrefix: String = "com.qavren.ghostcrab.profiles.corrupt."

    private static let log = PrivacySafeLogger(category: "profiles")

    /// `UserDefaults` is thread-safe but not `Sendable`; read from the
    /// nonisolated `loadProfilesNonIsolated()` path.
    private nonisolated(unsafe) let defaults: UserDefaults
    private let keychain: any TokenStoring
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder

    /// - Parameters:
    ///   - defaults: Backing `UserDefaults` store. Defaults to `.standard`.
    ///   - keychain: Token storage. Defaults to a fresh ``Keychain`` instance.
    public init(
        defaults: UserDefaults = .standard,
        keychain: any TokenStoring = Keychain()
    ) {
        self.defaults = defaults
        self.keychain = keychain
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        self.encoder = encoder
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        self.decoder = decoder
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    /// Saves or updates a profile. If a profile with the same `id` already
    /// exists it is replaced; otherwise the profile is appended.
    ///
    /// - Parameters:
    ///   - profile: The metadata to persist.
    ///   - token: Bearer token to store in the Keychain, or `nil` to clear any
    ///     existing token for `profile.id`.
    /// - Throws: ``KeychainError`` if the token write fails. The metadata
    ///   write is still committed on Keychain failure — the caller is expected
    ///   to surface the error and let the user retry, mirroring the Kotlin
    ///   `runCatching` semantics around `saveToken`.
    /// - Throws: ``ProfileStoreError/corruptProfileData(backupKey:operation:)``
    ///   (`.save`) if the stored list could not be decoded. Nothing is
    ///   written; the bad blob is backed up and cleared, so retrying succeeds.
    public func save(_ profile: ConnectionProfile, token: String?) async throws {
        var current = try loadProfilesForWrite(for: .save)
        current.removeAll { $0.id == profile.id }
        current.append(profile)
        writeProfiles(current)

        if let token {
            try keychain.setToken(token, for: profile.id.uuidString)
        } else {
            try keychain.deleteToken(for: profile.id.uuidString)
        }
    }

    /// Returns every stored profile, in insertion order. Empty if none exist
    /// or the stored blob is corrupt (the blob is left untouched; the next
    /// write backs it up).
    public func getAll() async -> [ConnectionProfile] {
        loadProfiles()
    }

    /// Returns the profile with `id`, or `nil` if none matches.
    public func get(id: String) async -> ConnectionProfile? {
        loadProfiles().first { $0.id.uuidString == id }
    }

    /// Deletes the profile with `id` and its associated Keychain token. No-op
    /// if no profile matches.
    ///
    /// - Throws: ``KeychainError`` if the Keychain delete fails for a reason
    ///   other than "not found". The metadata deletion still happens first, so
    ///   a leftover Keychain entry will not block UserDefaults cleanup — but
    ///   the caller should treat the throw as a partial failure.
    /// - Throws: ``ProfileStoreError/corruptProfileData(backupKey:operation:)``
    ///   (`.delete`) if the stored list could not be decoded. Nothing is
    ///   deleted; the bad blob is backed up and cleared, so the list starts empty.
    public func delete(id: String) async throws {
        var current = try loadProfilesForWrite(for: .delete)
        let originalCount = current.count
        current.removeAll { $0.id.uuidString == id }
        if current.count != originalCount {
            writeProfiles(current)
        }
        // Always attempt token cleanup, even if the metadata was already gone —
        // this is the path that heals an orphan Keychain entry left behind by
        // a previous partial failure.
        try keychain.deleteToken(for: id)
    }

    /// Removes every saved profile, every quarantined backup of an unreadable
    /// list, and every stored token, even when the live list cannot be
    /// decoded. Mirrors Android's `ConnectionProfileStore.clearAll()`.
    ///
    /// - Throws: ``KeychainError`` if the token wipe fails. The profile keys
    ///   are removed first, so no metadata survives a Keychain failure.
    public func clearAll() async throws {
        defaults.removeObject(forKey: Self.profilesKey)
        removeCorruptBackups()
        NotificationCenter.default.post(name: Self.changedNotification, object: nil)
        try keychain.deleteAllTokens()
    }

    // ── Token bridge ──────────────────────────────────────────────────────────

    /// Returns the stored token for `profileId`, or `nil` if none.
    ///
    /// Synchronous — delegates straight to ``Keychain/getToken(for:)``. Marked
    /// non-async to match the Kotlin call sites that do tight `getToken()`
    /// loops without an `await` boundary.
    public nonisolated func getToken(for profileId: String) throws -> String? {
        try keychain.getToken(for: profileId)
    }

    // ── Observation ───────────────────────────────────────────────────────────

    /// Emits the full profile list whenever ``save(_:token:)`` or
    /// ``delete(id:)`` runs. Replays the current value on subscription.
    ///
    /// Bridges `NotificationCenter` posts of
    /// ``changedNotification`` into an `AsyncStream`. The observer is removed
    /// when the stream terminates.
    public nonisolated func observeAll() -> AsyncStream<[ConnectionProfile]> {
        AsyncStream { continuation in
            // Replay current state synchronously so subscribers don't race the
            // first mutation.
            continuation.yield(loadProfilesNonIsolated())

            // `any NSObjectProtocol` is not Sendable; the token is only handed
            // back to NotificationCenter, which is thread-safe.
            nonisolated(unsafe) let observer = NotificationCenter.default.addObserver(
                forName: Self.changedNotification,
                object: nil,
                queue: nil
            ) { [weak self] _ in
                guard let self else { return }
                continuation.yield(self.loadProfilesNonIsolated())
            }

            continuation.onTermination = { _ in
                NotificationCenter.default.removeObserver(observer)
            }
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private func loadProfiles() -> [ConnectionProfile] {
        loadProfilesNonIsolated()
    }

    /// Read path for observation and queries. Never mutates storage: on an
    /// undecodable blob it logs and returns `[]`; the next write quarantines it.
    /// `UserDefaults` is itself thread-safe and the decoder is value-typed.
    private nonisolated func loadProfilesNonIsolated() -> [ConnectionProfile] {
        guard let data = defaults.data(forKey: Self.profilesKey) else {
            return []
        }
        do {
            return try decoder.decode([ConnectionProfile].self, from: data)
        } catch {
            Self.log.error("Stored profile list is unreadable; showing none until the next write backs it up", error: error)
            return []
        }
    }

    /// Read path for mutations. An undecodable blob is copied to a timestamped
    /// backup key (replacing any older backup), the live key is removed, and
    /// the write is refused, so a write never silently replaces data it could
    /// not read.
    ///
    /// - Parameter operation: The refused write, carried into the error.
    /// - Throws: ``ProfileStoreError/corruptProfileData(backupKey:operation:)``.
    private func loadProfilesForWrite(for operation: ProfileStoreError.Operation) throws -> [ConnectionProfile] {
        guard let data = defaults.data(forKey: Self.profilesKey) else {
            return []
        }
        do {
            return try decoder.decode([ConnectionProfile].self, from: data)
        } catch {
            let backupKey = Self.corruptBackupKeyPrefix + String(Int(Date().timeIntervalSince1970 * 1000))
            // Keep only the latest backup, like Android's single backup key.
            removeCorruptBackups()
            defaults.set(data, forKey: backupKey)
            defaults.removeObject(forKey: Self.profilesKey)
            NotificationCenter.default.post(name: Self.changedNotification, object: nil)
            Self.log.error("Quarantined unreadable profile list to \(backupKey)", error: error)
            throw ProfileStoreError.corruptProfileData(backupKey: backupKey, operation: operation)
        }
    }

    /// Removes every quarantined backup (keys under ``corruptBackupKeyPrefix``).
    private func removeCorruptBackups() {
        for key in defaults.dictionaryRepresentation().keys
        where key.hasPrefix(Self.corruptBackupKeyPrefix) {
            defaults.removeObject(forKey: key)
        }
    }

    private func writeProfiles(_ profiles: [ConnectionProfile]) {
        do {
            let data = try encoder.encode(profiles)
            defaults.set(data, forKey: Self.profilesKey)
            NotificationCenter.default.post(name: Self.changedNotification, object: nil)
        } catch {
            // Encoding a Codable struct of String / UUID / Date / Bool cannot
            // realistically fail; if it does the previous value is preserved
            // and no notification is posted.
        }
    }
}
