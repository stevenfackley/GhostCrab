import Testing
import Foundation
@testable import GhostCrab

/// `UserDefaults` is not `Sendable`, so a test cannot keep using an instance
/// it handed to the `ProfileStore` actor. Each test therefore gets a fresh
/// suite: the store is built with its own instance, and the test reads and
/// seeds the same suite through a second one.
@Suite("ProfileStore corrupt-data handling")
struct ProfileStoreTests {

    private func makeSuite() throws -> (name: String, defaults: UserDefaults) {
        let name = "ProfileStoreTests.\(UUID().uuidString)"
        return (name, try #require(UserDefaults(suiteName: name)))
    }

    private func makeStore(suite: String, tokens: InMemoryTokenStore = InMemoryTokenStore()) throws -> ProfileStore {
        ProfileStore(defaults: try #require(UserDefaults(suiteName: suite)), keychain: tokens)
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
        let (suite, defaults) = try makeSuite()
        let garbage = Data("not json".utf8)
        defaults.set(garbage, forKey: ProfileStore.profilesKey)
        let store = try makeStore(suite: suite)
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
        let (suite, defaults) = try makeSuite()
        defaults.set(Data("not json".utf8), forKey: ProfileStore.profilesKey)
        let store = try makeStore(suite: suite)

        await #expect(throws: ProfileStoreError.self) {
            try await store.delete(id: UUID().uuidString)
        }
        #expect(backupKeys(in: defaults).count == 1)
        #expect(defaults.data(forKey: ProfileStore.profilesKey) == nil)
    }

    @Test func corruptBlobErrorNamesTheBackupKey() async throws {
        let (suite, defaults) = try makeSuite()
        defaults.set(Data("not json".utf8), forKey: ProfileStore.profilesKey)
        let store = try makeStore(suite: suite)

        do {
            try await store.save(makeProfile(), token: nil)
            Issue.record("expected ProfileStoreError.corruptProfileData")
        } catch ProfileStoreError.corruptProfileData(let backupKey, let operation) {
            #expect(backupKey.hasPrefix(ProfileStore.corruptBackupKeyPrefix))
            #expect(defaults.data(forKey: backupKey) != nil)
            #expect(operation == .save)
            let message = ProfileStoreError.corruptProfileData(backupKey: backupKey, operation: operation).errorDescription ?? ""
            #expect(message.contains(backupKey))
            #expect(message.contains("Try again to save this gateway"))
        } catch {
            Issue.record("unexpected error: \(error)")
        }
    }

    @Test func corruptBlobOnDeleteSaysNothingWasDeleted() async throws {
        let (suite, defaults) = try makeSuite()
        defaults.set(Data("not json".utf8), forKey: ProfileStore.profilesKey)
        let store = try makeStore(suite: suite)

        do {
            try await store.delete(id: UUID().uuidString)
            Issue.record("expected ProfileStoreError.corruptProfileData")
        } catch let error as ProfileStoreError {
            switch error {
            case .corruptProfileData(let backupKey, let operation):
                #expect(operation == .delete)
                let message = error.errorDescription ?? ""
                #expect(message.contains(backupKey))
                #expect(message.contains("Nothing was deleted"))
                #expect(!message.contains("save this gateway"))
            }
        }
    }

    @Test func aNewerCorruptionPrunesTheOlderBackup() async throws {
        let (suite, defaults) = try makeSuite()
        let olderKey = ProfileStore.corruptBackupKeyPrefix + "1"
        defaults.set(Data("older garbage".utf8), forKey: olderKey)
        let garbage = Data("newer garbage".utf8)
        defaults.set(garbage, forKey: ProfileStore.profilesKey)
        let store = try makeStore(suite: suite)

        await #expect(throws: ProfileStoreError.self) {
            try await store.save(makeProfile(), token: nil)
        }

        let keys = backupKeys(in: defaults)
        #expect(keys.count == 1)
        let latest = try #require(keys.first)
        #expect(latest != olderKey)
        #expect(defaults.data(forKey: latest) == garbage)
        #expect(defaults.data(forKey: olderKey) == nil)
    }

    @Test func corruptBlobReadsAsEmptyWithoutMutatingStorage() async throws {
        let (suite, defaults) = try makeSuite()
        let garbage = Data("not json".utf8)
        defaults.set(garbage, forKey: ProfileStore.profilesKey)
        let store = try makeStore(suite: suite)

        #expect(await store.getAll().isEmpty)
        #expect(defaults.data(forKey: ProfileStore.profilesKey) == garbage)
        #expect(backupKeys(in: defaults).isEmpty)
    }

    @Test func roundTripsProfilesAndTokens() async throws {
        let (suite, _) = try makeSuite()
        let store = try makeStore(suite: suite)
        let profile = makeProfile()

        try await store.save(profile, token: "tok_123")

        #expect(await store.getAll() == [profile])
        #expect(try store.getToken(for: profile.id.uuidString) == "tok_123")
    }

    @Test func clearAllWipesProfilesBackupsAndTokens() async throws {
        let (suite, defaults) = try makeSuite()
        let tokens = InMemoryTokenStore()
        let store = try makeStore(suite: suite, tokens: tokens)
        try await store.save(makeProfile(), token: "tok_123")
        // Leave a quarantined backup behind, as an earlier corrupt write would.
        defaults.set(Data("not json".utf8), forKey: ProfileStore.corruptBackupKeyPrefix + "1")

        try await store.clearAll()

        #expect(await store.getAll().isEmpty)
        #expect(defaults.data(forKey: ProfileStore.profilesKey) == nil)
        #expect(backupKeys(in: defaults).isEmpty)
        #expect(tokens.count == 0)
    }

    @Test func clearAllRecoversFromAnUnreadableList() async throws {
        let (suite, defaults) = try makeSuite()
        defaults.set(Data("not json".utf8), forKey: ProfileStore.profilesKey)
        let store = try makeStore(suite: suite)

        try await store.clearAll()

        #expect(defaults.data(forKey: ProfileStore.profilesKey) == nil)
        try await store.save(makeProfile(), token: nil)
        #expect(await store.getAll().count == 1)
    }
}
