import Foundation

// MARK: - Client factory

/// Factory abstraction for ``OpenClawAPIClient`` creation.
///
/// Injectable into ``GatewayConnectionManagerImpl`` so tests can substitute a
/// mock client without touching the network. Mirrors Kotlin's
/// `OpenClawApiClientFactory` interface.
public protocol OpenClawAPIClientFactory: Sendable {
    func unauthenticated(baseURL: URL, allowCleartextPublicIPs: Bool) -> OpenClawAPIClient
    func authenticated(baseURL: URL, token: String, allowCleartextPublicIPs: Bool) -> OpenClawAPIClient
}

/// Default factory used in production — forwards to the static factories on
/// ``OpenClawAPIClient`` itself.
public struct DefaultOpenClawAPIClientFactory: OpenClawAPIClientFactory {
    public init() {}

    public func unauthenticated(baseURL: URL, allowCleartextPublicIPs: Bool) -> OpenClawAPIClient {
        OpenClawAPIClient.unauthenticated(baseURL: baseURL, allowCleartextPublicIPs: allowCleartextPublicIPs)
    }

    public func authenticated(baseURL: URL, token: String, allowCleartextPublicIPs: Bool) -> OpenClawAPIClient {
        OpenClawAPIClient.authenticated(baseURL: baseURL, token: token, allowCleartextPublicIPs: allowCleartextPublicIPs)
    }
}

// MARK: - Connection manager

/// Production implementation of ``GatewayConnectionManager``.
///
/// `actor` provides the same mutual exclusion as the Kotlin `Mutex` — every
/// `connect` / `disconnect` / `probeAuth` call is serialized through the actor.
/// The held ``OpenClawAPIClient`` is replaced on each ``connect(url:token:)`` and
/// released on ``disconnect()``.
///
/// The connection state is exposed via ``connectionStates()`` as an
/// `AsyncStream<GatewayConnection>`. Each call returns an independent stream
/// backed by the same underlying state — the latest value is replayed on
/// subscribe (mirroring Kotlin's `StateFlow.value`), and every subsequent
/// transition is yielded to all live subscribers. A synchronous snapshot is
/// available via ``currentState``.
///
/// Direct port of `GatewayConnectionManagerImpl.kt`.
public actor GatewayConnectionManagerImpl: GatewayConnectionManager {

    // ── State ────────────────────────────────────────────────────────────────

    /// Latest connection state. Read by ``currentState`` and replayed to every
    /// new subscriber.
    private var state: GatewayConnection = .disconnected

    /// Active subscribers — fanned out on every state transition. Removed on
    /// continuation termination.
    private var subscribers: [UUID: AsyncStream<GatewayConnection>.Continuation] = [:]

    /// Currently held client, or `nil` when disconnected. Replaced/released by
    /// ``connect(url:token:)`` and ``disconnect()``.
    private var activeClient: OpenClawAPIClient?

    private let clientFactory: any OpenClawAPIClientFactory
    private let settingsRepository: any SettingsRepository

    // ── Init ─────────────────────────────────────────────────────────────────

    /// Designated initializer.
    ///
    /// - Parameters:
    ///   - clientFactory: Creates ``OpenClawAPIClient`` instances. Injectable
    ///     for testing; defaults to ``DefaultOpenClawAPIClientFactory``.
    ///   - settingsRepository: Source for the `allowCleartextPublicIPs`
    ///     preference. Defaults to a stub that always blocks cleartext —
    ///     fail-safe identical to the Kotlin
    ///     `AlwaysBlockCleartextSettingsRepository`.
    public init(
        clientFactory: any OpenClawAPIClientFactory = DefaultOpenClawAPIClientFactory(),
        settingsRepository: any SettingsRepository = AlwaysBlockCleartextSettingsRepository()
    ) {
        self.clientFactory = clientFactory
        self.settingsRepository = settingsRepository
    }

    // ── GatewayConnectionManager: state ──────────────────────────────────────

    /// Synchronous snapshot of the current connection state.
    ///
    /// Lock-protected via ``SnapshotBox`` — does not hop through the actor, so
    /// callers can read it without `await`. Equivalent to Kotlin's
    /// `connectionState.value`. Writes happen only inside ``publish(_:)``
    /// (actor-isolated), so update ordering is preserved.
    public nonisolated var currentState: GatewayConnection {
        // The protocol declares this as a synchronous computed property, so we
        // must service the read without a hop. Each transition publishes the
        // value into an actor-isolated property *and* a nonisolated
        // synchronization primitive — see ``snapshotBox`` below.
        snapshotBox.value
    }

    /// Returns an `AsyncStream` that replays the latest state on subscribe and
    /// emits every subsequent transition. Each call yields an independent
    /// stream — multiple consumers are supported.
    public nonisolated func connectionStates() -> AsyncStream<GatewayConnection> {
        AsyncStream { continuation in
            let id = UUID()

            // Register first, *then* yield the latest snapshot from inside the
            // actor. This guarantees the subscriber's first event is the state
            // observed at registration time — no missed-transition race
            // between subscribe and the next publish.
            Task { [weak self] in
                guard let self else {
                    continuation.finish()
                    return
                }
                await self.registerAndReplay(id: id, continuation: continuation)
            }

            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                Task { await self.unregister(id: id) }
            }
        }
    }

    // ── GatewayConnectionManager: actions ────────────────────────────────────

    /// Probes auth requirements without storing any state.
    ///
    /// `/health` failure → ``GatewayError/unreachable``.
    /// `/status` 401/403 → `.token`. Otherwise → `.none`.
    ///
    /// - Parameter url: Base URL of the gateway.
    /// - Returns: The inferred ``AuthRequirement``.
    /// - Throws: ``GatewayError`` propagated from the probe.
    public func probeAuth(url: String) async throws -> AuthRequirement {
        let baseURL = try parseURL(url)
        let allowCleartext = await firstAllowCleartext()
        let probe = clientFactory.unauthenticated(
            baseURL: baseURL,
            allowCleartextPublicIPs: allowCleartext
        )
        do {
            _ = try await probe.health()
            do {
                _ = try await probe.status()
                await probe.close()
                return .none
            } catch let e as GatewayError {
                if case .auth = e {
                    await probe.close()
                    return .token
                }
                throw e
            }
        } catch {
            await probe.close()
            throw error
        }
    }

    /// Connects to a gateway. See ``GatewayConnectionManager/connect(url:token:)``.
    public func connect(url: String, token: String?) async throws {
        // Silently drop any prior session — same semantics as Kotlin.
        if let prior = activeClient {
            await prior.close()
            activeClient = nil
        }

        let baseURL = try parseURL(url)
        publish(.connecting(url: url))

        do {
            let allowCleartext = await firstAllowCleartext()
            let authReq = try await probeAuth(url: url)
            let client: OpenClawAPIClient
            if let token {
                client = clientFactory.authenticated(
                    baseURL: baseURL,
                    token: token,
                    allowCleartextPublicIPs: allowCleartext
                )
            } else {
                client = clientFactory.unauthenticated(
                    baseURL: baseURL,
                    allowCleartextPublicIPs: allowCleartext
                )
            }
            let statusResponse = try await client.status()
            let isHttps = url.lowercased().hasPrefix("https://")

            activeClient = client
            publish(.connected(
                url: url,
                displayName: statusResponse.displayName,
                version: statusResponse.version,
                authRequirement: authReq,
                isHttps: isHttps,
                capabilities: statusResponse.capabilities,
                hardwareInfo: statusResponse.hardware,
                tokenOrNull: token
            ))
        } catch let e as GatewayError {
            if let pending = activeClient {
                await pending.close()
                activeClient = nil
            }
            publish(.error(url: url, cause: e))
            throw e
        } catch {
            // Anything not already a GatewayError gets normalized to
            // .unreachable so callers see a typed error — same posture as
            // Kotlin's `safeRequest` catch-all.
            let mapped = GatewayError.unreachable(url: url, underlying: error as? (any Error & Sendable))
            if let pending = activeClient {
                await pending.close()
                activeClient = nil
            }
            publish(.error(url: url, cause: mapped))
            throw mapped
        }
    }

    /// Disconnects from the current gateway. No-op if already disconnected.
    public func disconnect() async {
        if let prior = activeClient {
            await prior.close()
            activeClient = nil
        }
        publish(.disconnected)
    }

    // ── Internal: client access for repository impls ─────────────────────────

    /// Returns the active client, or throws if disconnected.
    ///
    /// Equivalent to Kotlin's `requireClient()` — repository impls call this
    /// to obtain the live ``OpenClawAPIClient``. Throws a typed error rather
    /// than a Kotlin `IllegalStateException`.
    ///
    /// - Returns: The currently active ``OpenClawAPIClient``.
    /// - Throws: ``GatewayError/unreachable`` with a synthetic URL if no
    ///   connection is active. The choice of `.unreachable` matches the
    ///   "no active session" mental model: from the caller's perspective the
    ///   gateway is, for all intents, unreachable.
    public func requireClient() throws -> OpenClawAPIClient {
        guard let client = activeClient else {
            throw GatewayError.unreachable(url: "<no active connection>", underlying: nil)
        }
        return client
    }

    // ── Subscriber management ────────────────────────────────────────────────

    /// Registers a subscriber and yields the current actor-isolated state in
    /// the same atomic step — guarantees no transitions are missed between
    /// subscribe and the first emission.
    private func registerAndReplay(
        id: UUID,
        continuation: AsyncStream<GatewayConnection>.Continuation
    ) {
        subscribers[id] = continuation
        continuation.yield(state)
    }

    private func unregister(id: UUID) {
        subscribers.removeValue(forKey: id)
    }

    /// Publishes a new state to every subscriber and the snapshot box.
    ///
    /// The snapshot box is written first so a synchronous `currentState` read
    /// racing with the state transition observes the new value before any
    /// subscriber.
    private func publish(_ next: GatewayConnection) {
        state = next
        snapshotBox.set(next)
        for cont in subscribers.values {
            cont.yield(next)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private func firstAllowCleartext() async -> Bool {
        for await value in settingsRepository.allowCleartextPublicIPs() {
            return value
        }
        return false
    }

    private nonisolated func parseURL(_ raw: String) throws -> URL {
        guard let url = URL(string: raw) else {
            throw GatewayError.api(url: raw, statusCode: 0, body: "Malformed URL")
        }
        return url
    }

    // MARK: - Snapshot box

    /// Synchronous mailbox for the current state.
    ///
    /// Kotlin exposes `StateFlow<GatewayConnection>` whose `.value` is a
    /// non-suspending read. The Swift protocol mirrors that by declaring
    /// `currentState` as a synchronous computed property — which the actor
    /// cannot satisfy without a `nonisolated` escape hatch. ``SnapshotBox``
    /// is a `Sendable` lock-protected cell whose updates are sequenced by
    /// the actor (writes only happen inside ``publish(_:)``) but whose reads
    /// are synchronous and lock-only.
    private let snapshotBox = SnapshotBox()
}

// ── Snapshot box impl ────────────────────────────────────────────────────────

/// Lock-protected cell holding the latest ``GatewayConnection`` for synchronous
/// snapshot reads from any thread.
///
/// Necessary because ``GatewayConnectionManager/currentState`` is declared as a
/// synchronous computed property on the protocol — the actor cannot service
/// non-isolated reads of its mutable state without this indirection.
private final class SnapshotBox: @unchecked Sendable {
    private var stored: GatewayConnection = .disconnected
    private let lock = NSLock()

    var value: GatewayConnection {
        lock.lock(); defer { lock.unlock() }
        return stored
    }

    func set(_ next: GatewayConnection) {
        lock.lock(); defer { lock.unlock() }
        stored = next
    }
}

// ── Fail-safe default settings (no cleartext to public IPs) ──────────────────

/// Stub ``SettingsRepository`` that always reports cleartext-public-IPs as
/// disabled. Used as a fail-safe default in
/// ``GatewayConnectionManagerImpl/init(clientFactory:settingsRepository:)``.
///
/// Direct port of Kotlin's `AlwaysBlockCleartextSettingsRepository`.
public struct AlwaysBlockCleartextSettingsRepository: SettingsRepository {
    public init() {}

    public func allowCleartextPublicIPs() -> AsyncStream<Bool> {
        AsyncStream { continuation in
            continuation.yield(false)
            continuation.finish()
        }
    }

    public func setAllowCleartextPublicIPs(_ enabled: Bool) async {
        // no-op
    }
}
