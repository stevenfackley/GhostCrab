import Foundation

/// ``InstalledSkillRepository`` over the live session socket (ADR-0007): `skills.status` lists, `skills.install`
/// installs from ClawHub (one `res`, no progress events), `skills.update` enables or disables. The gateway has no
/// `skills.uninstall`, so ``uninstall(slug:)`` fails with ``GatewayError/methodUnsupported(url:method:)`` without
/// a call. State is held in a lock-protected box and fanned out to every ``observeInstalled()`` stream.
public final class InstalledSkillRepositoryImpl: InstalledSkillRepository, Sendable {

    /// Sent as `skills.install.timeoutMs`: the gateway downloads and verifies before it answers.
    static let installTimeoutMs = 120_000
    private static let installMargin: Duration = .seconds(10)
    /// `config.changed` follows a write within ~80 ms; without it the list is re-read anyway after this long.
    public static let defaultConfigChangedWait: Duration = .seconds(2)
    private static let configChangedEvent = "config.changed"
    private static let notConnectedURL = "<no active connection>"

    private let session: @Sendable () async throws -> GatewayRPCClient
    private let configChangedWait: Duration
    private let stateBox = InstalledSkillStateBox()

    /// - Parameters:
    ///   - session: The live session socket; throws while disconnected.
    ///   - configChangedWait: How long ``setSkillEnabled(skillKey:enabled:)`` waits for `config.changed`.
    public init(
        session: @escaping @Sendable () async throws -> GatewayRPCClient,
        configChangedWait: Duration = InstalledSkillRepositoryImpl.defaultConfigChangedWait
    ) {
        self.session = session
        self.configChangedWait = configChangedWait
    }

    /// - Parameter connectionManager: Source of the live session socket.
    public convenience init(connectionManager: GatewayConnectionManagerImpl) {
        self.init(session: { try await connectionManager.requireSession() })
    }

    /// Hot list; starts empty, updated by every `skills.status` read and by an install whose re-read failed.
    public func observeInstalled() -> AsyncStream<[InstalledSkill]> {
        stateBox.subscribe()
    }

    /// One-shot `skills.status`.
    ///
    /// - Returns: The listed skills, or the failure: ``GatewayError/protocolViolation(url:detail:)`` for a payload
    ///   without a `skills` array or an entry without a string `skillKey` / `name` (the whole list fails rather
    ///   than an entry vanishing), ``GatewayError/unreachable(url:underlying:)`` while disconnected, or the typed
    ///   RPC error.
    public func refreshFromGateway() async -> Result<[InstalledSkill], any Error> {
        do {
            return .success(try await fetchStatus(try await session()))
        } catch {
            return .failure(error)
        }
    }

    /// Emits `.connecting`, `.applying("skills.install")`, then `.succeeded` (the refreshed entry) or `.failed`, and
    /// finishes. A cancelled stream gets no terminal event.
    ///
    /// - Parameters:
    ///   - slug: ClawHub reference, e.g. `@steipete/weather`.
    ///   - version: `nil` → latest.
    public func install(slug: String, version: String?) -> AsyncStream<SkillInstallProgress> {
        AsyncStream { continuation in
            continuation.yield(.connecting(target: slug))
            let task = Task { [weak self] in
                defer { continuation.finish() }
                guard let self else { return }
                do {
                    let rpc = try await self.session()
                    continuation.yield(.applying(step: "skills.install"))
                    let result = try await rpc.request(
                        "skills.install",
                        params: SkillsInstallParams(
                            slug: slug,
                            version: version,
                            timeoutMs: InstalledSkillRepositoryImpl.installTimeoutMs
                        ),
                        timeout: .milliseconds(InstalledSkillRepositoryImpl.installTimeoutMs)
                            + InstalledSkillRepositoryImpl.installMargin
                    )
                    let installed = try await self.installedSkill(after: rpc, requested: slug, result: result?.objectValue)
                    continuation.yield(.succeeded(installed: installed))
                } catch is CancellationError {
                    // The caller cancelled the stream: no terminal event.
                } catch let error as GatewayError {
                    continuation.yield(.failed(error: error.toSkillInstallError(slug: slug)))
                } catch {
                    continuation.yield(.failed(error: .unknown(cause: String(describing: type(of: error)))))
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    /// OpenClaw has no skill-removal RPC (QA §3); nothing is sent. Use ``setSkillEnabled(skillKey:enabled:)``.
    ///
    /// - Returns: A ``GatewayError/methodUnsupported(url:method:)`` failure for `skills.uninstall`; a
    ///   `CancellationError` failure when the caller was cancelled while the session was looked up.
    public func uninstall(slug: String) async -> Result<Void, any Error> {
        let url: String
        do {
            url = try await session().endpoint
        } catch {
            if error is CancellationError || Task.isCancelled { return .failure(CancellationError()) }
            url = Self.notConnectedURL // disconnected: still unsupported, named without a gateway
        }
        return .failure(GatewayError.methodUnsupported(url: url, method: "skills.uninstall"))
    }

    /// `skills.update {skillKey, enabled}`, then a `skills.status` re-read once the gateway emits `config.changed`
    /// (the list is stale until then, and the event can precede the response; after the wait cap without it the
    /// list is re-read anyway; a socket that ends during the wait ends it at once and the re-read fails typed).
    /// The gateway answers `ok:true` even for unknown keys, so the key is first checked against a fresh
    /// `skills.status` of this socket: a cached list could come from another gateway.
    ///
    /// - Parameters:
    ///   - skillKey: The skill's key; an `owner/slug` is reduced to `slug`.
    ///   - enabled: `false` to disable.
    /// - Returns: Success after the re-read; a ``GatewayError/api(url:statusCode:body:)`` 404 failure for a key
    ///   `skills.status` does not list (nothing is written), or the typed RPC error.
    public func setSkillEnabled(skillKey: String, enabled: Bool) async -> Result<Void, any Error> {
        do {
            let rpc = try await session()
            let key = Self.bareKey(skillKey)
            guard try await fetchStatus(rpc).contains(where: { $0.slug == key }) else {
                return .failure(GatewayError.api(
                    url: rpc.endpoint,
                    statusCode: 404,
                    body: "skill \(key) is not listed by skills.status"
                ))
            }
            // Subscribed before the write goes out, so an early config.changed is kept (events are not replayed).
            let changes = await rpc.events()
            let wait = configChangedWait
            try await withThrowingTaskGroup(of: Void.self) { group in
                group.addTask { await InstalledSkillRepositoryImpl.awaitConfigChanged(changes, timeout: wait) }
                // A failed write leaves the group early, which cancels the wait and drops the subscription.
                _ = try await rpc.request("skills.update", params: SkillsUpdateParams(skillKey: key, enabled: enabled))
                try await group.waitForAll()
            }
            _ = try await fetchStatus(rpc)
            return .success(())
        } catch {
            return .failure(error)
        }
    }

    /// Returns at the first `config.changed` in `events`, when `events` finishes (the socket ended), or after
    /// `timeout`, whichever comes first.
    static func awaitConfigChanged(_ events: AsyncStream<GatewayEventFrame>, timeout: Duration) async {
        let name = configChangedEvent
        await withTaskGroup(of: Void.self) { group in
            group.addTask {
                for await event in events where event.event == name { return }
            }
            group.addTask { _ = try? await Task.sleep(for: timeout) }
            await group.next()
            group.cancelAll()
        }
    }

    private func fetchStatus(_ rpc: GatewayRPCClient) async throws -> [InstalledSkill] {
        guard let entries = try await rpc.request("skills.status")?.objectValue?["skills"]?.value as? [AnyCodable] else {
            throw GatewayError.protocolViolation(url: rpc.endpoint, detail: "skills.status without a skills array")
        }
        let list = try entries.map { try Self.installedSkill(from: $0, url: rpc.endpoint) }
        stateBox.set(list)
        return list
    }

    /// The install already succeeded; this only finds its entry. `skills.install` answers with the bare key and the
    /// installed version (`{ok, message, slug, version, targetDir, …}`). A failed or incomplete refresh falls back
    /// to a synthetic entry rather than reporting a successful install as failed; when the refresh failed, that
    /// entry also joins the observed list.
    ///
    /// - Throws: `CancellationError` only, when the caller was cancelled during the refresh: nothing is added then.
    private func installedSkill(after rpc: GatewayRPCClient, requested: String, result: [String: AnyCodable]?) async throws -> InstalledSkill {
        let key = result?["slug"]?.value as? String ?? Self.bareKey(requested)
        let refreshed: [InstalledSkill]?
        do {
            refreshed = try await fetchStatus(rpc)
        } catch {
            if error is CancellationError || Task.isCancelled { throw CancellationError() }
            refreshed = nil
        }
        if let found = refreshed?.first(where: { $0.slug == key }) { return found }
        let synthetic = InstalledSkill(
            slug: key,
            installedVersion: result?["version"]?.value as? String ?? "",
            source: .clawHub,
            installedAt: Date(timeIntervalSince1970: 0)
        )
        if refreshed == nil { stateBox.upsert(synthetic) }
        return synthetic
    }

    /// One `skills.status` entry. Strict about the key: an entry that is not an object or has no string `skillKey`
    /// (falling back to `name`) fails the whole list. Version and install time come from `clawhub` when present.
    private static func installedSkill(from entry: AnyCodable, url: String) throws -> InstalledSkill {
        guard let object = entry.objectValue else {
            throw GatewayError.protocolViolation(url: url, detail: "skills.status entry is not an object")
        }
        guard let key = (object["skillKey"]?.value as? String) ?? (object["name"]?.value as? String) else {
            throw GatewayError.protocolViolation(url: url, detail: "skills.status entry without a skillKey or name")
        }
        let clawhub = object["clawhub"]?.objectValue
        let installedAtMs = number(clawhub?["installedAt"]) ?? 0
        return InstalledSkill(
            slug: key,
            installedVersion: (clawhub?["installedVersion"]?.value as? String) ?? (object["version"]?.value as? String) ?? "",
            source: SkillSource.from(
                statusSource: object["source"]?.value as? String,
                bundled: object["bundled"]?.value as? Bool ?? false,
                clawhub: clawhub != nil
            ),
            installedAt: Date(timeIntervalSince1970: installedAtMs / 1000),
            disabled: object["disabled"]?.value as? Bool ?? false
        )
    }

    /// A JSON number as `Double` (integers decode as `Int`), or `nil`.
    private static func number(_ value: AnyCodable?) -> Double? {
        guard let raw = value?.value else { return nil }
        if let int = raw as? Int { return Double(int) }
        return raw as? Double
    }

    /// `@owner/key` and `owner/key` installs are listed under their bare key.
    private static func bareKey(_ slug: String) -> String {
        slug.split(separator: "/").last.map(String.init) ?? slug
    }
}

// ── State box ────────────────────────────────────────────────────────────────

/// Lock-protected mutable list of installed skills with multi-subscriber fan-out.
///
/// Replays the latest list on subscribe and yields every change to all live
/// subscribers — same semantics as Kotlin's `MutableStateFlow<List<InstalledSkill>>`.
private final class InstalledSkillStateBox: @unchecked Sendable {
    private var stored: [InstalledSkill] = []
    private var subscribers: [UUID: AsyncStream<[InstalledSkill]>.Continuation] = [:]
    private let lock = NSLock()

    func subscribe() -> AsyncStream<[InstalledSkill]> {
        AsyncStream { continuation in
            let id = UUID()
            self.lock.lock()
            let initial = self.stored
            self.subscribers[id] = continuation
            self.lock.unlock()

            continuation.yield(initial)

            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                self.lock.lock()
                self.subscribers.removeValue(forKey: id)
                self.lock.unlock()
            }
        }
    }

    func set(_ next: [InstalledSkill]) {
        lock.lock()
        stored = next
        let snapshot = next
        let subs = subscribers.values
        lock.unlock()
        for cont in subs {
            cont.yield(snapshot)
        }
    }

    func upsert(_ skill: InstalledSkill) {
        lock.lock()
        stored.removeAll { $0.slug == skill.slug }
        stored.append(skill)
        let snapshot = stored
        let subs = subscribers.values
        lock.unlock()
        for cont in subs {
            cont.yield(snapshot)
        }
    }
}

// ── Wire types ───────────────────────────────────────────────────────────────

/// `skills.install` params (2026.10.3 `SkillsInstallParams`, ClawHub form). A `nil` version is omitted (latest).
struct SkillsInstallParams: Encodable, Sendable {
    let source = "clawhub"
    let slug: String
    let version: String?
    let force = false
    let timeoutMs: Int
}

/// `skills.update` params (2026.10.3 `SkillsUpdateParams`).
struct SkillsUpdateParams: Encodable, Sendable {
    let skillKey: String
    let enabled: Bool
}

extension SkillSource {
    /// `skills.status` entry → ``SkillSource``. Verified against 2026.10.3: a ClawHub install reports
    /// `source: "openclaw-workspace"` plus a `clawhub {…}` object; bundled and plugin skills (`openclaw-bundled`,
    /// `openclaw-extra`) and hand-made workspace skills have no `clawhub` object and are local.
    ///
    /// - Parameters:
    ///   - source: The reported `source`.
    ///   - bundled: The reported `bundled` flag.
    ///   - clawhub: Whether the entry carries a `clawhub` object.
    /// - Returns: The mapped source.
    static func from(statusSource source: String?, bundled: Bool, clawhub: Bool) -> SkillSource {
        if clawhub { return .clawHub }
        guard let source else { return .unknown }
        if bundled || source.hasPrefix("openclaw-") { return .local }
        return .unknown
    }
}

extension GatewayError {
    /// Maps a ``GatewayError`` to the install-time ``SkillInstallError``
    /// taxonomy used by the install progress stream.
    ///
    /// - missingScope → `.unauthorized(missingScope:)`
    /// - rpc `UNAVAILABLE` "not found on ClawHub" → `.notFound(slug:)`; other
    ///   rpc → `.protocol(code:message:)` with the gateway's string code
    /// - methodUnsupported → `.protocol(code: "INVALID_REQUEST", …)`
    /// - auth / profileNeedsReauth / pairingRequired / connectRejected →
    ///   `.unauthorized(missingScope: "operator.admin")`
    /// - connectionClosed → `.protocol(code: "WS <close code>", …)` (not
    ///   retryable: the gateway refused the socket)
    /// - REST leftovers (removed with the REST client): api 404 →
    ///   `.notFound(slug:)`, 409 → `.dependencyConflict(conflicts:)`, 422 →
    ///   `.verificationFailed(expected:actual:)`, other →
    ///   `.protocol(code: "HTTP <status>", …)`. That includes a 200 whose body
    ///   is not an installed skill: `installSkill` reports it as
    ///   `.api(statusCode: 200, body: "Response deserialization failed …")`,
    ///   which lands here as `.protocol(code: "HTTP 200", message: <that body>)`.
    /// - timeout / unreachable / tls → `.network(cause:)`
    /// - configValidation → `.protocol(code: "CONFIG_VALIDATION", …)`;
    ///   protocolViolation → `.protocol(code: "PROTOCOL", …)` (not retryable)
    /// - cleartextBlocked → `.cleartextBlocked(message:)` (not retryable; the
    ///   message carries the fix and is shown as is)
    /// - AI cases and deviceCredentialStorage → `.unknown(cause:)`
    ///
    /// Internal (not private) so tests can pin the mapping.
    ///
    /// - Parameter slug: The slug being installed, for `.notFound`. When empty,
    ///   the gateway's message stands in for it.
    /// - Returns: The install error for this gateway error.
    func toSkillInstallError(slug: String = "") -> SkillInstallError {
        switch self {
        case let .missingScope(_, _, scope):
            return .unauthorized(missingScope: scope)
        case let .rpc(_, _, code, _, message, _, _, _):
            if code == "UNAVAILABLE", message.contains("not found on ClawHub") {
                return .notFound(slug: slug.isEmpty ? message : slug)
            }
            return .protocol(code: code, message: message)
        case let .methodUnsupported(_, method):
            return .protocol(code: "INVALID_REQUEST", message: "unknown method: \(method)")
        case .auth, .profileNeedsReauth, .pairingRequired, .connectRejected:
            return .unauthorized(missingScope: "operator.admin")
        case let .connectionClosed(url, closeCode, reason):
            return .protocol(code: "WS \(closeCode)", message: reason.isEmpty ? "gateway closed the connection at \(url)" : reason)
        case let .api(_, statusCode, body):
            let bodyText = body ?? ""
            switch statusCode {
            case 404:
                return .notFound(slug: bodyText)
            case 409:
                return .dependencyConflict(conflicts: bodyText.split(separator: ",").map { String($0) })
            case 422:
                return .verificationFailed(expected: "", actual: bodyText)
            default:
                return .protocol(code: "HTTP \(statusCode)", message: bodyText)
            }
        case let .timeout(url, _):
            return .network(cause: "timeout at \(url)")
        case let .unreachable(url, _):
            return .network(cause: "unreachable at \(url)")
        case let .tls(url, _):
            return .network(cause: "TLS error at \(url)")
        case let .configValidation(field, reason):
            return .protocol(code: "CONFIG_VALIDATION", message: "\(field): \(reason)")
        case let .protocolViolation(url, detail):
            return .protocol(code: "PROTOCOL", message: "\(detail) at \(url)")
        case .cleartextBlocked:
            return .cleartextBlocked(message: errorDescription ?? "Cleartext HTTP blocked by app policy")
        case let .aiServiceUnavailable(url):
            return .unknown(cause: "ai service unavailable at \(url)")
        case let .aiQuotaExceeded(url):
            return .unknown(cause: "ai quota exceeded at \(url)")
        case let .deviceCredentialStorage(operation, _):
            return .unknown(cause: "could not \(operation) in the Keychain")
        }
    }
}
