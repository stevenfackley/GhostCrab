import Foundation

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

    private let defaults: UserDefaults
    private let keychain: Keychain
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder

    /// - Parameters:
    ///   - defaults: Backing `UserDefaults` store. Defaults to `.standard`.
    ///   - keychain: Token storage. Defaults to a fresh ``Keychain`` instance.
    public init(
        defaults: UserDefaults = .standard,
        keychain: Keychain = Keychain()
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
    public func save(_ profile: ConnectionProfile, token: String?) async throws {
        var current = loadProfiles()
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
    /// or the stored blob is corrupt.
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
    public func delete(id: String) async throws {
        var current = loadProfiles()
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

            let observer = NotificationCenter.default.addObserver(
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

    /// Same as ``loadProfiles()`` but callable from non-isolated context.
    /// `UserDefaults` is itself thread-safe and the decoder is value-typed.
    private nonisolated func loadProfilesNonIsolated() -> [ConnectionProfile] {
        guard let data = defaults.data(forKey: Self.profilesKey) else {
            return []
        }
        return (try? decoder.decode([ConnectionProfile].self, from: data)) ?? []
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
