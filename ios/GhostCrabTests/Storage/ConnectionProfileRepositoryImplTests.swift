import Testing
import Foundation
@testable import GhostCrab

@Suite("ConnectionProfileRepositoryImpl token recovery")
struct ConnectionProfileRepositoryImplTests {

    private func makeRepo() throws -> (ConnectionProfileRepositoryImpl, ProfileStore, InMemoryTokenStore) {
        let defaults = try #require(UserDefaults(suiteName: "RepoTests.\(UUID().uuidString)"))
        let tokens = InMemoryTokenStore()
        let store = ProfileStore(defaults: defaults, keychain: tokens)
        return (ConnectionProfileRepositoryImpl(store: store), store, tokens)
    }

    private func makeProfile() -> ConnectionProfile {
        ConnectionProfile(id: UUID(), displayName: "Basement", url: "http://192.168.1.20:18789", lastConnectedAt: nil, hasToken: true)
    }

    private func expectReauth(_ repo: ConnectionProfileRepositoryImpl, profileId: String) async {
        do {
            _ = try await repo.getToken(profileId: profileId)
            Issue.record("expected GatewayError.profileNeedsReauth")
        } catch GatewayError.profileNeedsReauth(let id) {
            #expect(id == profileId)
        } catch {
            Issue.record("unexpected error: \(error)")
        }
    }

    @Test func readableTokenIsReturned() async throws {
        let (repo, _, _) = try makeRepo()
        let profile = makeProfile()
        try await repo.saveProfile(profile, token: "tok_123")

        #expect(try await repo.getToken(profileId: profile.id.uuidString) == "tok_123")
    }

    @Test func unreadableTokenThrowsReauthAndClearsTheEntry() async throws {
        let (repo, store, tokens) = try makeRepo()
        let profile = makeProfile()
        try await repo.saveProfile(profile, token: "tok_123")
        tokens.simulateFailures(reads: true, deletes: false)

        await expectReauth(repo, profileId: profile.id.uuidString)

        #expect(await store.getAll().isEmpty)
        #expect(tokens.token(for: profile.id.uuidString) == nil)
    }

    @Test func failedCleanupStillThrowsReauth() async throws {
        let (repo, store, tokens) = try makeRepo()
        let profile = makeProfile()
        try await repo.saveProfile(profile, token: "tok_123")
        tokens.simulateFailures(reads: true, deletes: true)

        await expectReauth(repo, profileId: profile.id.uuidString)

        // Metadata goes first; the token is orphaned (and logged) until the next save/delete.
        #expect(await store.getAll().isEmpty)
        #expect(tokens.token(for: profile.id.uuidString) == "tok_123")
    }

    @Test func clearAllRemovesEveryProfileAndToken() async throws {
        let (repo, store, tokens) = try makeRepo()
        try await repo.saveProfile(makeProfile(), token: "tok_1")
        try await repo.saveProfile(makeProfile(), token: "tok_2")

        try await repo.clearAll()

        #expect(await store.getAll().isEmpty)
        #expect(tokens.count == 0)
    }
}
