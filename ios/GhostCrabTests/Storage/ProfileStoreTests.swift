import Testing
import Foundation
@testable import GhostCrab

@Suite("ProfileStore corrupt-data handling")
struct ProfileStoreTests {

    private func makeDefaults() throws -> UserDefaults {
        try #require(UserDefaults(suiteName: "ProfileStoreTests.\(UUID().uuidString)"))
    }

    private func makeProfile() -> ConnectionProfile {
        ConnectionProfile(id: UUID(), displayName: "Basement", url: "http://192.168.1.20:18789", lastConnectedAt: nil, hasToken: false)
    }

    private func backupKeys(in defaults: UserDefaults) -> [String] {
        defaults.dictionaryRepresentation().keys.filter {
            $0.hasPrefix(ProfileStore.corruptBackupKeyPrefix)
        }
    }

    @Test func corruptBlobIsQuarantinedAndFirstWriteThrows() async throws {
        let defaults = try makeDefaults()
        let garbage = Data("not json".utf8)
        defaults.set(garbage, forKey: ProfileStore.profilesKey)
        let store = ProfileStore(defaults: defaults, keychain: InMemoryTokenStore())
        let profile = makeProfile()

        await #expect(throws: ProfileStoreError.self) {
            try await store.save(profile, token: nil)
        }

        let keys = backupKeys(in: defaults)
        #expect(keys.count == 1)
        let backupKey = try #require(keys.first)
        #expect(defaults.data(forKey: backupKey) == garbage)
        #expect(defaults.data(forKey: ProfileStore.profilesKey) == nil)

        // The retry starts clean; the old blob stays recoverable under backupKey.
        try await store.save(profile, token: nil)
        #expect(await store.getAll() == [profile])
        #expect(defaults.data(forKey: backupKey) == garbage)
    }

    @Test func corruptBlobMakesDeleteThrowToo() async throws {
        let defaults = try makeDefaults()
        defaults.set(Data("not json".utf8), forKey: ProfileStore.profilesKey)
        let store = ProfileStore(defaults: defaults, keychain: InMemoryTokenStore())

        await #expect(throws: ProfileStoreError.self) {
            try await store.delete(id: UUID().uuidString)
        }
        #expect(backupKeys(in: defaults).count == 1)
        #expect(defaults.data(forKey: ProfileStore.profilesKey) == nil)
    }

    @Test func corruptBlobErrorNamesTheBackupKey() async throws {
        let defaults = try makeDefaults()
        defaults.set(Data("not json".utf8), forKey: ProfileStore.profilesKey)
        let store = ProfileStore(defaults: defaults, keychain: InMemoryTokenStore())

        do {
            try await store.save(makeProfile(), token: nil)
            Issue.record("expected ProfileStoreError.corruptProfileData")
        } catch ProfileStoreError.corruptProfileData(let backupKey) {
            #expect(backupKey.hasPrefix(ProfileStore.corruptBackupKeyPrefix))
            #expect(defaults.data(forKey: backupKey) != nil)
            let message = ProfileStoreError.corruptProfileData(backupKey: backupKey).errorDescription ?? ""
            #expect(message.contains(backupKey))
        } catch {
            Issue.record("unexpected error: \(error)")
        }
    }

    @Test func corruptBlobReadsAsEmptyWithoutMutatingStorage() async throws {
        let defaults = try makeDefaults()
        let garbage = Data("not json".utf8)
        defaults.set(garbage, forKey: ProfileStore.profilesKey)
        let store = ProfileStore(defaults: defaults, keychain: InMemoryTokenStore())

        #expect(await store.getAll().isEmpty)
        #expect(defaults.data(forKey: ProfileStore.profilesKey) == garbage)
        #expect(backupKeys(in: defaults).isEmpty)
    }

    @Test func roundTripsProfilesAndTokens() async throws {
        let tokens = InMemoryTokenStore()
        let store = ProfileStore(defaults: try makeDefaults(), keychain: tokens)
        let profile = makeProfile()

        try await store.save(profile, token: "tok_123")

        #expect(await store.getAll() == [profile])
        #expect(try store.getToken(for: profile.id.uuidString) == "tok_123")
    }

    @Test func clearAllWipesProfilesBackupsAndTokens() async throws {
        let defaults = try makeDefaults()
        let tokens = InMemoryTokenStore()
        let store = ProfileStore(defaults: defaults, keychain: tokens)
        let profile = makeProfile()
        try await store.save(profile, token: "tok_123")
        // Leave a quarantined backup behind, as an earlier corrupt write would.
        defaults.set(Data("not json".utf8), forKey: ProfileStore.corruptBackupKeyPrefix + "1")

        try await store.clearAll()

        #expect(await store.getAll().isEmpty)
        #expect(defaults.data(forKey: ProfileStore.profilesKey) == nil)
        #expect(backupKeys(in: defaults).isEmpty)
        #expect(tokens.count == 0)
    }

    @Test func clearAllRecoversFromAnUnreadableList() async throws {
        let defaults = try makeDefaults()
        defaults.set(Data("not json".utf8), forKey: ProfileStore.profilesKey)
        let store = ProfileStore(defaults: defaults, keychain: InMemoryTokenStore())

        try await store.clearAll()

        #expect(defaults.data(forKey: ProfileStore.profilesKey) == nil)
        try await store.save(makeProfile(), token: nil)
        #expect(await store.getAll().count == 1)
    }
}
