import Foundation
import Testing
@testable import GhostCrab

@Suite("InstalledSkillRepositoryImpl (skills.*)")
struct InstalledSkillRepositoryImplTests {

    private func makeRepo(
        _ replies: [String: [String]],
        eventsBeforeReply: [String: [String]] = [:],
        closeAfterReplyTo: Set<String> = [],
        configChangedWait: Duration = InstalledSkillRepositoryImpl.defaultConfigChangedWait
    ) async throws -> (InstalledSkillRepositoryImpl, ScriptedGatewayWebSocket) {
        let socket = try ScriptedGatewayWebSocket(
            challenge: false,
            replies: replies,
            eventsBeforeReply: eventsBeforeReply,
            closeAfterReplyTo: closeAfterReplyTo
        )
        let rpc = await startedRPC(socket)
        return (InstalledSkillRepositoryImpl(session: { rpc }, configChangedWait: configChangedWait), socket)
    }

    private func events(_ stream: AsyncStream<SkillInstallProgress>) async -> [SkillInstallProgress] {
        var collected: [SkillInstallProgress] = []
        for await event in stream { collected.append(event) }
        return collected
    }

    private func status(_ payload: String) -> String {
        #"{"type":"res","id":"x","ok":true,"payload":"# + payload + "}"
    }

    // ── skills.status ────────────────────────────────────────────────────────

    @Test func refreshMapsSkillsStatus() async throws {
        let (repo, _) = try await makeRepo(try .fixtures(["skills.status": "res-skills-status"]))
        let skills = try await repo.refreshFromGateway().get()
        #expect(skills.map(\.slug) == ["1password", "browser-automation", "weather"])
        #expect(skills[0].disabled)
        #expect(skills[0].source == .local)
        #expect(skills[0].installedVersion == "")
        #expect(!skills[1].disabled)
        #expect(skills[1].source == .local)
        #expect(skills[2].source == .clawHub)
        #expect(skills[2].installedVersion == "1.0.0")
        #expect(Int(skills[2].installedAt.timeIntervalSince1970) == 1_792_035_309)
    }

    @Test(arguments: [#"{"skills":"none"}"#, #"{"workspaceDir":"/w"}"#, #"[]"#])
    func aStatusWithoutASkillsArrayIsAProtocolViolation(payload: String) async throws {
        let (repo, _) = try await makeRepo(["skills.status": [status(payload)]])
        guard case .failure(let error) = await repo.refreshFromGateway(),
              case .protocolViolation? = error as? GatewayError else {
            Issue.record("expected protocolViolation for \(payload)")
            return
        }
    }

    @Test func aSkillEntryWithoutAKeyFailsTheRefreshInsteadOfVanishing() async throws {
        let (repo, _) = try await makeRepo(["skills.status": [status(#"{"skills":[{"skillKey":"ok"},{"source":"openclaw-extra"}]}"#)]])
        guard case .failure(let error) = await repo.refreshFromGateway(),
              case .protocolViolation? = error as? GatewayError else {
            Issue.record("expected protocolViolation")
            return
        }
    }

    // ── skills.install ───────────────────────────────────────────────────────

    @Test func installSendsTheClawHubParamsAndEndsSucceeded() async throws {
        let (repo, socket) = try await makeRepo(try .fixtures([
            "skills.install": "res-skills-install",
            "skills.status": "res-skills-status",
        ]))
        let progress = await events(repo.install(slug: "@steipete/weather", version: "1.0.0"))
        #expect(progress.first == .connecting(target: "@steipete/weather"))
        #expect(progress.contains(.applying(step: "skills.install")))
        guard case .succeeded(let installed)? = progress.last else {
            Issue.record("expected succeeded, got \(progress)")
            return
        }
        #expect(installed.slug == "weather")
        #expect(installed.installedVersion == "1.0.0")
        #expect(installed.source == .clawHub)
        let params = try await sentParams(socket, "skills.install")
        #expect(params["source"] as? String == "clawhub")
        #expect(params["slug"] as? String == "@steipete/weather")
        #expect(params["version"] as? String == "1.0.0")
        #expect(params["force"] as? Bool == false)
        #expect(params["timeoutMs"] as? Int == 120_000)
        #expect(await socket.sentMethods() == ["skills.install", "skills.status"])
    }

    @Test func anInstallWithoutAVersionSendsNone() async throws {
        let (repo, socket) = try await makeRepo(try .fixtures([
            "skills.install": "res-skills-install",
            "skills.status": "res-skills-status",
        ]))
        _ = await events(repo.install(slug: "@steipete/weather", version: nil))
        #expect(try await sentParams(socket, "skills.install")["version"] == nil)
    }

    @Test func anInstallWhoseListRefreshFailsStillSucceedsFromTheInstallAnswer() async throws {
        var replies = try [String: [String]].fixtures(["skills.install": "res-skills-install"])
        replies["skills.status"] = [try GatewayFixtures.text("err-missing-scope")]
        let (repo, _) = try await makeRepo(replies)
        let progress = await events(repo.install(slug: "@steipete/weather", version: nil))
        guard case .succeeded(let installed)? = progress.last else {
            Issue.record("expected succeeded, got \(progress)")
            return
        }
        #expect(installed.slug == "weather")
        #expect(installed.installedVersion == "1.0.0")
        #expect(installed.source == .clawHub)
        var observed: [InstalledSkill] = []
        for await list in repo.observeInstalled() {
            observed = list
            break
        }
        #expect(observed.map(\.slug) == ["weather"])
    }

    @Test func anUnknownSlugEndsNotFound() async throws {
        let (repo, _) = try await makeRepo(try .fixtures(["skills.install": "err-skill-not-found"]))
        let progress = await events(repo.install(slug: "qa-nonexistent-skill-zz9", version: nil))
        #expect(progress.last == .failed(error: .notFound(slug: "qa-nonexistent-skill-zz9")))
    }

    @Test func aMissingAdminScopeEndsUnauthorized() async throws {
        let (repo, _) = try await makeRepo(try .fixtures(["skills.install": "err-missing-scope"]))
        let progress = await events(repo.install(slug: "x", version: nil))
        #expect(progress.last == .failed(error: .unauthorized(missingScope: "operator.read")))
    }

    @Test func uninstallIsUnsupportedWithoutACall() async throws {
        let (repo, socket) = try await makeRepo([:])
        guard case .failure(let error) = await repo.uninstall(slug: "browser-automation"),
              case .methodUnsupported(_, "skills.uninstall")? = error as? GatewayError else {
            Issue.record("expected methodUnsupported")
            return
        }
        #expect(await socket.sentMethods().isEmpty)
    }

    // ── skills.update ────────────────────────────────────────────────────────

    @Test(.timeLimit(.minutes(1)))
    func disableObservesAnEarlyConfigChangedThenReReadsTheList() async throws {
        let (repo, socket) = try await makeRepo(
            try .fixtures(["skills.update": "res-skills-update", "skills.status": "res-skills-status"]),
            // Like the real gateway: config.changed can arrive before the skills.update response.
            eventsBeforeReply: ["skills.update": [try GatewayFixtures.text("evt-config-changed")]],
            configChangedWait: .seconds(30)
        )
        let clock = ContinuousClock()
        let started = clock.now
        guard case .success = await repo.setSkillEnabled(skillKey: "someone/browser-automation", enabled: false) else {
            Issue.record("expected success")
            return
        }
        // The early event ended the wait (subscribed before the write), not the 30 s cap.
        #expect(clock.now - started < .seconds(10))
        let params = try await sentParams(socket, "skills.update")
        #expect(params["skillKey"] as? String == "browser-automation")
        #expect(params["enabled"] as? Bool == false)
        #expect(await socket.sentMethods() == ["skills.status", "skills.update", "skills.status"])
    }

    @Test func withoutConfigChangedTheListIsReReadAfterTheWaitCap() async throws {
        let (repo, socket) = try await makeRepo(
            try .fixtures(["skills.update": "res-skills-update", "skills.status": "res-skills-status"]),
            configChangedWait: .milliseconds(300)
        )
        let clock = ContinuousClock()
        let started = clock.now
        guard case .success = await repo.setSkillEnabled(skillKey: "browser-automation", enabled: true) else {
            Issue.record("expected success")
            return
        }
        #expect(clock.now - started >= .milliseconds(300))
        #expect(try await sentParams(socket, "skills.update")["enabled"] as? Bool == true)
        #expect(await socket.sentMethods() == ["skills.status", "skills.update", "skills.status"])
    }

    @Test(.timeLimit(.minutes(1)))
    func theSocketEndingDuringTheWaitFailsTypedWithoutWaitingOutTheCap() async throws {
        let (repo, _) = try await makeRepo(
            try .fixtures(["skills.update": "res-skills-update", "skills.status": "res-skills-status"]),
            closeAfterReplyTo: ["skills.update"],
            configChangedWait: .seconds(30)
        )
        let clock = ContinuousClock()
        let started = clock.now
        guard case .failure(let error) = await repo.setSkillEnabled(skillKey: "browser-automation", enabled: false),
              case .connectionClosed(_, 1008, _)? = error as? GatewayError else {
            Issue.record("expected connectionClosed")
            return
        }
        #expect(clock.now - started < .seconds(10))
    }

    @Test func aKeySkillsStatusDoesNotListIsRefusedWithoutAWrite() async throws {
        let (repo, socket) = try await makeRepo(try .fixtures(["skills.status": "res-skills-status"]))
        guard case .failure(let error) = await repo.setSkillEnabled(skillKey: "qa-does-not-exist", enabled: false),
              case .api(_, 404, _)? = error as? GatewayError else {
            Issue.record("expected api 404")
            return
        }
        #expect(await socket.sentMethods() == ["skills.status"])
    }

    @Test func aKeyFromAnEarlierListIsCheckedAgainstAFreshSkillsStatus() async throws {
        let (repo, socket) = try await makeRepo([
            "skills.status": [
                try GatewayFixtures.text("res-skills-status"),
                status(#"{"skills":[{"skillKey":"only-here","source":"openclaw-extra"}]}"#),
            ],
        ])
        _ = try await repo.refreshFromGateway().get()
        // The gateway no longer lists browser-automation.
        guard case .failure(let error) = await repo.setSkillEnabled(skillKey: "browser-automation", enabled: false),
              case .api(_, 404, _)? = error as? GatewayError else {
            Issue.record("expected api 404")
            return
        }
        #expect(!(await socket.sentMethods().contains("skills.update")))
    }

    @Test func aMissingAdminScopeFailsTheToggleTyped() async throws {
        let (repo, _) = try await makeRepo(try .fixtures([
            "skills.status": "res-skills-status",
            "skills.update": "err-missing-scope",
        ]))
        guard case .failure(let error) = await repo.setSkillEnabled(skillKey: "browser-automation", enabled: false),
              case .missingScope? = error as? GatewayError else {
            Issue.record("expected missingScope")
            return
        }
    }

    @Test func togglingWhileDisconnectedFailsUnreachable() async {
        let repo = InstalledSkillRepositoryImpl(session: {
            throw GatewayError.unreachable(url: "<no active connection>", underlying: nil)
        })
        guard case .failure(let error) = await repo.setSkillEnabled(skillKey: "weather", enabled: false),
              case .unreachable? = error as? GatewayError else {
            Issue.record("expected unreachable")
            return
        }
    }

    @Test func statusEntriesMapToSkillSource() {
        #expect(SkillSource.from(statusSource: "openclaw-workspace", bundled: false, clawhub: true) == .clawHub)
        #expect(SkillSource.from(statusSource: "openclaw-workspace", bundled: false, clawhub: false) == .local)
        #expect(SkillSource.from(statusSource: "openclaw-bundled", bundled: true, clawhub: false) == .local)
        #expect(SkillSource.from(statusSource: "openclaw-extra", bundled: false, clawhub: false) == .local)
        #expect(SkillSource.from(statusSource: "workspace", bundled: false, clawhub: false) == .unknown)
        #expect(SkillSource.from(statusSource: nil, bundled: false, clawhub: false) == .unknown)
    }
}
