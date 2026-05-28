import Foundation

/// REST-backed ``InstalledSkillRepository``.
///
/// The Kotlin counterpart (`InstalledSkillRepositoryImpl.kt`) uses the
/// gateway's JSON-RPC WebSocket (`skills.list`, `skills.install`,
/// `skills.uninstall`) and streams intermediate progress notifications. The
/// iOS port talks to the equivalent REST endpoints on
/// ``OpenClawAPIClient`` instead — `getInstalledSkills()`,
/// `installSkill(slug:version:)`, `uninstallSkill(slug:)`. The REST install is
/// synchronous, so the install stream emits only:
///
/// - `.connecting(target:)` on subscribe, and
/// - a terminal `.succeeded(installed:)` or `.failed(error:)`.
///
/// The intermediate Kotlin phases (`downloading` / `verifying` / `applying`)
/// are unavailable over REST; the UI gracefully shows a single spinner
/// between connect and terminal.
///
/// State is held in a lock-protected box and republished to every observer via
/// an `AsyncStream` for each ``observeInstalled()`` call.
public final class InstalledSkillRepositoryImpl: InstalledSkillRepository, @unchecked Sendable {

    private let connectionManager: GatewayConnectionManagerImpl
    private let stateBox = InstalledSkillStateBox()

    /// - Parameter connectionManager: Concrete manager used to obtain the
    ///   active ``OpenClawAPIClient``.
    public init(connectionManager: GatewayConnectionManagerImpl) {
        self.connectionManager = connectionManager
    }

    /// Hot stream of the current installed-skill list. Starts empty; updated
    /// on successful install/uninstall and after ``refreshFromGateway()``.
    public func observeInstalled() -> AsyncStream<[InstalledSkill]> {
        stateBox.subscribe()
    }

    /// One-shot refresh from gateway `GET /api/skills`.
    public func refreshFromGateway() async -> Result<[InstalledSkill], any Error> {
        do {
            let client = try await connectionManager.requireClient()
            let dtos = try await client.getInstalledSkills()
            let list = dtos.map { $0.toInstalledSkill() }
            stateBox.set(list)
            return .success(list)
        } catch {
            return .failure(error)
        }
    }

    /// Start an install. The returned stream emits ``SkillInstallProgress``
    /// events and finishes after the terminal `.succeeded` / `.failed`.
    public func install(slug: String, version: String?) -> AsyncStream<SkillInstallProgress> {
        AsyncStream { continuation in
            continuation.yield(.connecting(target: slug))

            let task = Task { [weak self] in
                guard let self else {
                    continuation.finish()
                    return
                }
                do {
                    let client = try await self.connectionManager.requireClient()
                    let dto = try await client.installSkill(slug: slug, version: version)
                    let installed = dto.toInstalledSkill()
                    self.stateBox.upsert(installed)
                    continuation.yield(.succeeded(installed: installed))
                } catch let e as GatewayError {
                    continuation.yield(.failed(error: e.toSkillInstallError()))
                } catch is CancellationError {
                    // Caller cancelled the stream — no terminal event.
                } catch {
                    continuation.yield(.failed(error: .unknown(cause: error.localizedDescription)))
                }
                continuation.finish()
            }

            continuation.onTermination = { _ in
                task.cancel()
            }
        }
    }

    /// Remove a previously-installed skill via `POST /api/skills/uninstall`.
    public func uninstall(slug: String) async -> Result<Void, any Error> {
        do {
            let client = try await connectionManager.requireClient()
            try await client.uninstallSkill(slug: slug)
            stateBox.remove(slug: slug)
            return .success(())
        } catch {
            return .failure(error)
        }
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

    func remove(slug: String) {
        lock.lock()
        stored.removeAll { $0.slug == slug }
        let snapshot = stored
        let subs = subscribers.values
        lock.unlock()
        for cont in subs {
            cont.yield(snapshot)
        }
    }
}

// ── Mapping ──────────────────────────────────────────────────────────────────

private extension InstalledSkillDTO {
    func toInstalledSkill() -> InstalledSkill {
        let resolvedSource: SkillSource
        switch source?.lowercased() {
        case "clawhub": resolvedSource = .clawHub
        case "local": resolvedSource = .local
        default: resolvedSource = .unknown
        }
        // Kotlin treats `installed_at` as epoch millis; default to 0 when absent.
        let resolvedDate: Date
        if let millis = self.installedAt {
            resolvedDate = Date(timeIntervalSince1970: TimeInterval(millis) / 1000)
        } else {
            resolvedDate = Date(timeIntervalSince1970: 0)
        }
        return InstalledSkill(
            slug: slug,
            installedVersion: installedVersion,
            source: resolvedSource,
            installedAt: resolvedDate
        )
    }
}

private extension GatewayError {
    /// Maps REST-level ``GatewayError`` cases to the install-time
    /// ``SkillInstallError`` taxonomy used by the install progress stream.
    ///
    /// Mirrors the Kotlin `WsRpcException.toInstallError()` mapping:
    /// - auth → `.unauthorized(missingScope: "operator.admin")`
    /// - api 404 → `.notFound(slug:)` (slug is unknown at this point; pass body)
    /// - api 409 → `.dependencyConflict(conflicts:)`
    /// - api 422 → `.verificationFailed(expected:actual:)`
    /// - api other → `.protocol(rpcCode:message:)`
    /// - timeout / unreachable → `.network(cause:)`
    /// - everything else → `.unknown(cause:)`
    func toSkillInstallError() -> SkillInstallError {
        switch self {
        case .auth:
            return .unauthorized(missingScope: "operator.admin")
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
                return .protocol(rpcCode: statusCode, message: bodyText)
            }
        case let .timeout(url, _):
            return .network(cause: "timeout at \(url)")
        case let .unreachable(url, _):
            return .network(cause: "unreachable at \(url)")
        case let .tls(url, _):
            return .network(cause: "TLS error at \(url)")
        case let .configValidation(field, reason):
            return .protocol(rpcCode: 0, message: "\(field): \(reason)")
        case .profileNeedsReauth:
            return .unauthorized(missingScope: "operator.admin")
        case let .aiServiceUnavailable(url):
            return .unknown(cause: "ai service unavailable at \(url)")
        case let .aiQuotaExceeded(url):
            return .unknown(cause: "ai quota exceeded at \(url)")
        }
    }
}
