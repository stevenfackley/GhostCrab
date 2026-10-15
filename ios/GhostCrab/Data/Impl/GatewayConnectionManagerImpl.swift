import Foundation

// MARK: - Client factory

/// Builds the REST client used for `GET /health` before the control-plane socket opens (ADR-0007).
/// Injectable so tests can substitute `MockURLProtocol`-backed clients. Mirrors Kotlin's `OpenClawApiClientFactory`.
public protocol OpenClawAPIClientFactory: Sendable {
    func unauthenticated(baseURL: URL, allowCleartextPublicIPs: Bool) -> OpenClawAPIClient
}

/// Default factory used in production.
public struct DefaultOpenClawAPIClientFactory: OpenClawAPIClientFactory {
    public init() {}

    public func unauthenticated(baseURL: URL, allowCleartextPublicIPs: Bool) -> OpenClawAPIClient {
        OpenClawAPIClient.unauthenticated(baseURL: baseURL, allowCleartextPublicIPs: allowCleartextPublicIPs)
    }
}

// MARK: - Connection manager

/// Production ``GatewayConnectionManager`` (ADR-0007): `GET /health` (reachability and the cleartext policy, before
/// any credential leaves the device), then one control-plane WebSocket per session opened with a signed `connect`.
/// Repositories call RPCs on ``requireSession()``.
///
/// Actor methods interleave at every `await`, so `connect` and `disconnect` make their state transition
/// synchronously, before their first suspension: the latest call always wins. A newer `connect` or a `disconnect`
/// supersedes a connect still in flight, closes what it opened (its handshake then fails at once), and the
/// superseded `connect` throws `CancellationError` without publishing anything.
///
/// The live socket is watched. When it ends (the app was suspended, a gateway restart, a network change, a 1008
/// close) the manager makes ONE silent reconnect to the same URL with the same identity and this gateway's stored
/// device token (the shared token when none is stored), like Android. Meanwhile the state is `.connected` with
/// `reconnecting: true` and ``requireSession()`` fails with `.unreachable` naming the URL; a successful reconnect
/// publishes a new `.connected` (next `sessionGeneration`). If the reconnect fails, the state becomes `.error`: with the
/// gateway's refusal when it sent one (`.pairingRequired`: the dashboard then shows "Connection lost" with the pairing message and a Retry, or
/// `.connectRejected`), else with the lost socket's end cause (a transport failure, or `.deviceCredentialStorage`
/// while the Keychain is locked, never retried here). A session the reconnect opened earns another silent reconnect
/// only after ``reconnectRearmInterval`` live on the injected clock, so a gateway that keeps dropping the socket never
/// loops while a blip hours later is still covered. A `connect` or `disconnect` during the reconnect supersedes it
/// like any connect in flight.
///
/// Every socket and REST client is closed exactly once on every path (failed handshake, caller cancellation,
/// supersession, disconnect, socket loss, the auth probe): a ``GatewayRPCClient`` lives until it is closed.
///
/// State is exposed through ``connectionStates()`` (latest value replayed on subscribe) and the synchronous
/// ``currentState`` snapshot; ``connectionWarnings()`` reports what did not fail the connection.
public actor GatewayConnectionManagerImpl: GatewayConnectionManager {

    /// The live session: REST client (`/health`), control-plane socket, and the hello that opened it.
    private struct ActiveSession {
        let url: String
        /// ``GatewayKey/of(_:)`` of `url`.
        let key: String
        /// The shared token the user connected with (also in `.connected(tokenOrNull:)`). Sensitive.
        let token: String?
        let client: OpenClawAPIClient
        let rpc: GatewayRPCClient
        let hello: GatewayHello
        /// `hello-ok` issued a device token that could not be stored.
        let deviceTokenNotSaved: Bool
        /// `true` for a session the silent reconnect opened, `false` for one a `connect` opened.
        let reconnected: Bool
        /// `GatewayConnection.connected(sessionGeneration:)` of this session; set when it goes live.
        var generation = 0
        /// The manager's elapsed time when the session went live.
        var liveSince: Duration = .zero

        /// - Parameter now: The manager's elapsed time when the socket ended.
        /// - Returns: Whether losing this socket may trigger the silent reconnect: always for a session a `connect`
        ///   opened; for a reconnected one only after ``GatewayConnectionManagerImpl/reconnectRearmInterval`` live.
        func mayReconnect(now: Duration) -> Bool {
            !reconnected || now - liveSince >= GatewayConnectionManagerImpl.reconnectRearmInterval
        }
    }

    /// How long a reconnected session must stay live before losing its socket may trigger another silent reconnect.
    public static let reconnectRearmInterval: Duration = .seconds(5 * 60)

    private static let deviceTokenMismatch = "AUTH_DEVICE_TOKEN_MISMATCH"
    private static let credentialMissingCodes: Set<String> = ["AUTH_TOKEN_MISSING", "AUTH_PASSWORD_MISSING", "AUTH_REQUIRED"]
    private static let defaultDisplayName = "OpenClaw Gateway"
    private static let log = PrivacySafeLogger(category: "gateway")

    // ── State ────────────────────────────────────────────────────────────────

    private var state: GatewayConnection = .disconnected
    private var subscribers: [UUID: AsyncStream<GatewayConnection>.Continuation] = [:]
    private var active: ActiveSession?
    private var watcher: Task<Void, Never>?
    /// The connect in flight, if any (for a newer call to supersede).
    private var inFlight: ConnectAttempt?
    /// Bumped by every `connect` and `disconnect`; an attempt whose id differs has been superseded.
    private var generation: UInt64 = 0
    /// `GatewayConnection.connected(sessionGeneration:)` of the latest session made live.
    private var sessionGeneration = 0
    private var warnings: [ConnectionWarning] = []
    /// Gateways that accepted this run's regenerated identity (a `hello-ok`).
    private var reapprovedGateways: Set<String> = []

    private let identityProvider: any DeviceIdentityProviding
    private let deviceTokens: any DeviceTokenStoring
    private let clientInfo: GatewayClientInfo
    private let clientFactory: any OpenClawAPIClientFactory
    private let socketFactory: any GatewaySocketFactory
    private let settingsRepository: any SettingsRepository
    private let challengeTimeout: Duration
    /// Monotonic time since the manager was created, on the injected clock.
    private let elapsed: @Sendable () -> Duration

    // ── Init ─────────────────────────────────────────────────────────────────

    /// - Parameters:
    ///   - identityProvider: This install's Ed25519 identity (one ``DeviceIdentityStore`` per process).
    ///   - deviceTokens: Per-gateway device tokens from earlier `hello-ok`s.
    ///   - clientInfo: `connect.params.client`; defaults to this build's.
    ///   - clientFactory: Builds the REST client for `/health`. Injectable for tests.
    ///   - socketFactory: Builds the control-plane socket. Injectable for tests.
    ///   - settingsRepository: Source of the cleartext-to-public-IP opt-in. Defaults to always-block.
    ///   - challengeTimeout: How long a handshake waits for `connect.challenge`.
    ///   - clock: Measures how long a reconnected session stayed live (``reconnectRearmInterval``). The default keeps
    ///     counting while the device sleeps; tests pass a clock they advance themselves.
    public init(
        identityProvider: any DeviceIdentityProviding,
        deviceTokens: any DeviceTokenStoring,
        clientInfo: GatewayClientInfo = .current(),
        clientFactory: any OpenClawAPIClientFactory = DefaultOpenClawAPIClientFactory(),
        socketFactory: any GatewaySocketFactory = URLSessionGatewaySocketFactory(),
        settingsRepository: any SettingsRepository = AlwaysBlockCleartextSettingsRepository(),
        challengeTimeout: Duration = GatewayHandshake.challengeTimeout,
        clock: any Clock<Duration> = ContinuousClock()
    ) {
        self.identityProvider = identityProvider
        self.deviceTokens = deviceTokens
        self.clientInfo = clientInfo
        self.clientFactory = clientFactory
        self.socketFactory = socketFactory
        self.settingsRepository = settingsRepository
        self.challengeTimeout = challengeTimeout
        self.elapsed = GatewayConnectionManagerImpl.elapsedReader(clock)
    }

    /// - Returns: The time elapsed on `clock` since this call.
    private static func elapsedReader<C: Clock<Duration>>(_ clock: C) -> @Sendable () -> Duration {
        let origin = clock.now
        return { origin.duration(to: clock.now) }
    }

    // ── GatewayConnectionManager: state ──────────────────────────────────────

    /// Synchronous snapshot of the current state (lock-protected; no actor hop).
    public nonisolated var currentState: GatewayConnection {
        snapshotBox.value
    }

    /// Replays the latest state on subscribe, then every transition. Each call yields an independent stream.
    public nonisolated func connectionStates() -> AsyncStream<GatewayConnection> {
        AsyncStream { continuation in
            let id = UUID()
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

    /// Additive (v1.1) — see docs/adr/0005-additive-repository-methods.md
    ///
    /// - Returns: ``ConnectionWarning/deviceTokenNotSaved`` while the live session's device token could not be
    ///   stored; ``ConnectionWarning/deviceIdentityRegenerated`` with an error while this run's replaced identity
    ///   has not been accepted by that gateway yet. Empty while disconnected or connecting.
    public func connectionWarnings() async -> [ConnectionWarning] {
        warnings
    }

    // ── GatewayConnectionManager: actions ────────────────────────────────────

    /// ADR-0007 decision 8: `/health`, then a credential-less signed `connect`. `AUTH_TOKEN_MISSING`,
    /// `AUTH_PASSWORD_MISSING` or `AUTH_REQUIRED` → `.token`; `hello-ok` → its auth mode; `PAIRING_REQUIRED` →
    /// `.none` (only the approval is missing). Stores nothing and changes no state; the socket and the REST client
    /// are closed before returning, on every path.
    ///
    /// - Throws: ``GatewayError`` from `/health` or the handshake; `CancellationError` when the caller is cancelled.
    public func probeAuth(url: String) async throws -> AuthRequirement {
        let baseURL = try parseURL(url)
        let allowCleartext = await firstAllowCleartext()
        let client = clientFactory.unauthenticated(baseURL: baseURL, allowCleartextPublicIPs: allowCleartext)
        var rpc: GatewayRPCClient?
        let outcome: Result<AuthRequirement, any Error>
        do {
            _ = try await client.health()
            let identity = try await identityProvider.identity()
            let socket = try await openRPC(baseURL: baseURL, allowCleartext: allowCleartext)
            rpc = socket
            let hello = try await GatewayHandshake.perform(
                rpc: socket, client: clientInfo, auth: .none, identity: identity, challengeTimeout: challengeTimeout
            )
            outcome = .success(hello.authRequirement(token: nil))
        } catch GatewayError.connectRejected(_, let detailCode, _, _, _, _) where Self.credentialMissingCodes.contains(detailCode) {
            outcome = .success(AuthRequirement.token)
        } catch GatewayError.pairingRequired {
            outcome = .success(AuthRequirement.none)
        } catch {
            outcome = .failure(error)
        }
        await release(OpenedResources(client: client, rpc: rpc))
        switch outcome {
        case .success(let requirement):
            return requirement
        case .failure(let error):
            if error is CancellationError || Task.isCancelled { throw CancellationError() }
            throw Self.typed(error, url: url)
        }
    }

    /// Connects with the shared gateway token, or (`nil`) with this gateway's stored device token or no credential.
    /// See ``GatewayConnectionManager/connect(url:token:)``. Replaces the live session and supersedes a connect in
    /// flight, the silent reconnect included. A cancelled handshake ends `.disconnected` and throws
    /// `CancellationError`; so does a connect that a newer `connect` or `disconnect` superseded (it publishes nothing).
    /// A device token that cannot be stored after a successful `hello-ok` never fails the connect: it is reported by
    /// ``connectionWarnings()``.
    ///
    /// - Throws: `.pairingRequired`, `.connectRejected` (a revoked device token is forgotten first), `.rpc`,
    ///   `.deviceCredentialStorage`, `.protocolViolation`, or a transport error.
    public func connect(url: String, token: String?) async throws {
        generation &+= 1
        let attempt = ConnectAttempt(id: generation)
        let superseded = inFlight?.take()
        inFlight = attempt
        let previous = detachActive()
        warnings = []
        publish(.connecting(url: url))
        await release(superseded)
        await release(previous)

        let session: ActiveSession
        do {
            session = try await openSession(url: url, token: token, attempt: attempt, reconnecting: false)
        } catch {
            try await fail(attempt, url: url, error: error)
        }
        guard await goLive(session, attempt: attempt) else { throw CancellationError() }
    }

    /// Disconnects from the current gateway and supersedes a connect in flight (the silent reconnect included). No-op
    /// on the network if already disconnected. Publishes `.disconnected` before closing anything, so a newer call's
    /// state always wins.
    public func disconnect() async {
        generation &+= 1
        let superseded = inFlight?.take()
        inFlight = nil
        let previous = detachActive()
        warnings = []
        publish(.disconnected)
        await release(superseded)
        await release(previous)
    }

    // ── Session access for repository impls ─────────────────────────────────

    /// - Returns: The live session's socket.
    /// - Throws: ``GatewayError/unreachable(url:underlying:)`` while there is none: with the gateway URL while the
    ///   silent reconnect runs, else `<no active connection>`.
    public func requireSession() throws -> GatewayRPCClient {
        guard let session = active else {
            if case let .connected(url, _, _, _, _, _, _, _, true, _) = state {
                throw GatewayError.unreachable(url: url, underlying: nil)
            }
            throw GatewayError.unreachable(url: "<no active connection>", underlying: nil)
        }
        return session.rpc
    }

    /// - Returns: `hello-ok.auth.scopes` of the live session, or `nil` while disconnected.
    public func grantedScopes() -> Set<String>? {
        active.map { Set($0.hello.scopes) }
    }

    // ── Session lifecycle ────────────────────────────────────────────────────

    /// `/health`, socket, signed `connect`, device-token bookkeeping. Everything opened is held by `attempt` until
    /// the caller takes it over.
    ///
    /// - Parameter reconnecting: `true` for the silent reconnect: the stored device token goes first, the shared
    ///   token is the fallback.
    /// - Throws: ``SupersededAttempt`` as soon as a newer call replaced `attempt`; `CancellationError` for a cancelled
    ///   caller (checked before each network step); otherwise the failure.
    private func openSession(url: String, token: String?, attempt: ConnectAttempt,
                             reconnecting: Bool) async throws -> ActiveSession {
        let baseURL = try parseURL(url)
        let allowCleartext = await firstAllowCleartext()
        try ensureCurrent(attempt)
        let rest = clientFactory.unauthenticated(baseURL: baseURL, allowCleartextPublicIPs: allowCleartext)
        attempt.hold(rest)
        _ = try await rest.health() // reachability + cleartext policy, before any credential is sent
        try ensureCurrent(attempt)
        let identity = try await identityProvider.identity()
        try ensureCurrent(attempt)
        let key = GatewayKey.of(url)
        let stored = deviceTokens.load(gatewayKey: key).flatMap { $0.deviceId == identity.deviceId ? $0 : nil }
        let socket = try await openRPC(baseURL: baseURL, allowCleartext: allowCleartext)
        attempt.hold(socket)
        try ensureCurrent(attempt)
        let auth = GatewayConnectAuth.select(sharedToken: reconnecting && stored != nil ? nil : token, stored: stored)
        let hello = try await GatewayHandshake.perform(
            rpc: socket, client: clientInfo, auth: auth, identity: identity, challengeTimeout: challengeTimeout
        )
        let saved = rememberDeviceToken(hello, key: key, identity: identity, stored: stored)
        return ActiveSession(url: url, key: key, token: token, client: rest, rpc: socket, hello: hello,
                             deviceTokenNotSaved: !saved, reconnected: reconnecting)
    }

    /// Makes `session` live (a new session generation), watches its socket and publishes `.connected`, unless a newer
    /// `connect` or `disconnect` superseded `attempt` meanwhile: then closes what the attempt opened.
    ///
    /// - Returns: `false` when superseded.
    private func goLive(_ session: ActiveSession, attempt: ConnectAttempt) async -> Bool {
        let regenerated = await identityProvider.regenerated
        guard attempt.id == generation else {
            await release(attempt.take())
            return false
        }
        _ = attempt.take() // now owned by the session
        inFlight = nil
        if regenerated { reapprovedGateways.insert(session.key) }
        warnings = session.deviceTokenNotSaved ? [.deviceTokenNotSaved] : []
        sessionGeneration += 1
        var live = session
        live.generation = sessionGeneration
        live.liveSince = elapsed()
        active = live
        watch(live)
        publish(Self.connectedState(live))
        return true
    }

    /// A failed `connect`: publishes the outcome (unless superseded), closes what the attempt opened, throws.
    ///
    /// - Throws: `CancellationError` when the caller was cancelled or the attempt was superseded; else the typed error.
    private func fail(_ attempt: ConnectAttempt, url: String, error: any Error) async throws -> Never {
        let regenerated = await identityProvider.regenerated
        let opened = attempt.take()
        guard attempt.id == generation else {
            await release(opened) // the superseding call owns the state
            throw CancellationError()
        }
        inFlight = nil
        if error is CancellationError || error is SupersededAttempt || Task.isCancelled {
            warnings = []
            publish(.disconnected)
            await release(opened)
            throw CancellationError()
        }
        let mapped = Self.typed(error, url: url)
        let key = GatewayKey.of(url)
        if case let .connectRejected(_, detailCode, _, _, _, _) = mapped, detailCode == Self.deviceTokenMismatch {
            deviceTokens.clear(gatewayKey: key) // revoked: the next connect falls back to the shared token or pairing
        }
        warnings = regenerated && !reapprovedGateways.contains(key) ? [.deviceIdentityRegenerated] : []
        publish(.error(url: url, cause: mapped))
        await release(opened)
        throw mapped
    }

    private func ensureCurrent(_ attempt: ConnectAttempt) throws {
        guard attempt.id == generation else { throw SupersededAttempt() }
        try Task.checkCancellation()
    }

    private func openRPC(baseURL: URL, allowCleartext: Bool) async throws -> GatewayRPCClient {
        let socketURL = try GatewayWebSocketURL.make(from: baseURL, allowCleartextPublicIPs: allowCleartext)
        let rpc = GatewayRPCClient(socket: socketFactory.makeSocket(url: socketURL), endpoint: socketURL.absoluteString)
        await rpc.start()
        return rpc
    }

    /// Stores the device token `hello-ok` issued. A storage failure must not fail a connect that already succeeded:
    /// the session works without it, so it becomes ``ConnectionWarning/deviceTokenNotSaved``.
    ///
    /// - Returns: `false` when an issued token could not be stored.
    private func rememberDeviceToken(_ hello: GatewayHello, key: String, identity: DeviceIdentity, stored: StoredDeviceToken?) -> Bool {
        guard let issued = hello.deviceToken else { return true }
        // Upstream rule: the same token keeps its stored scopes; a new token takes this socket's live scopes.
        let scopes = (stored?.token == issued ? stored?.scopes : nil) ?? hello.scopes
        do {
            try deviceTokens.save(StoredDeviceToken(token: issued, scopes: scopes, role: hello.role, deviceId: identity.deviceId),
                                  gatewayKey: key)
            return true
        } catch {
            // Type name only: never anything that could carry the token.
            Self.log.warning("Device token not stored (\(type(of: error))); this session works without it")
            return false
        }
    }

    /// Reacts as soon as the gateway drops the session socket: the silent reconnect, or `.error` with its end cause.
    private func watch(_ session: ActiveSession) {
        let rpc = session.rpc
        watcher = Task { [weak self] in
            let events = await rpc.events()
            for await _ in events {}
            guard !Task.isCancelled else { return } // stopped on purpose
            await self?.socketEnded(rpc)
        }
    }

    private func socketEnded(_ rpc: GatewayRPCClient) async {
        let cause = await rpc.endCause ?? .unreachable(url: rpc.endpoint, underlying: nil)
        guard let lost = active, lost.rpc === rpc else { return } // closed on purpose or replaced meanwhile
        active = nil
        watcher = nil
        let mayReconnect = lost.mayReconnect(now: elapsed())
        if mayReconnect {
            publish(Self.connectedState(lost, reconnecting: true))
        }
        let observed = generation
        await release(OpenedResources(client: lost.client, rpc: lost.rpc))
        guard observed == generation else { return } // a connect or disconnect owns the state now
        guard mayReconnect else {
            warnings = []
            publish(.error(url: lost.url, cause: cause))
            return
        }
        await reconnect(lost, cause: cause)
    }

    /// The single silent reconnect after `lost`'s socket ended with `cause`. Registered as the connect in flight, so a
    /// newer `connect` or `disconnect` supersedes it (and closes what it opened). Never retried here.
    private func reconnect(_ lost: ActiveSession, cause: GatewayError) async {
        let attempt = ConnectAttempt(id: generation)
        inFlight = attempt
        let session: ActiveSession
        do {
            session = try await openSession(url: lost.url, token: lost.token, attempt: attempt, reconnecting: true)
        } catch {
            await reconnectFailed(attempt, lost: lost, cause: cause, error: error)
            return
        }
        _ = await goLive(session, attempt: attempt)
    }

    /// A failed silent reconnect: closes what the attempt opened, then publishes `.error` (unless superseded, before
    /// or while closing). Closing first means whoever observes the `.error` finds the attempt's socket already closed,
    /// like Android, which releases before it sets Error. The gateway's own refusal (`.pairingRequired`,
    /// `.connectRejected`) is the actionable error, so it is reported; any other failure (transport,
    /// `.deviceCredentialStorage`) reports the lost socket's `cause`.
    private func reconnectFailed(_ attempt: ConnectAttempt, lost: ActiveSession, cause: GatewayError,
                                 error: any Error) async {
        let regenerated = await identityProvider.regenerated
        let opened = attempt.take()
        guard attempt.id == generation else {
            await release(opened) // the superseding call owns the state
            return
        }
        inFlight = nil
        let reported: GatewayError
        if let refusal = error as? GatewayError, Self.isGatewayRefusal(refusal) {
            reported = refusal
        } else {
            reported = cause
        }
        if case let .connectRejected(_, detailCode, _, _, _, _) = reported, detailCode == Self.deviceTokenMismatch {
            deviceTokens.clear(gatewayKey: lost.key) // revoked: the next connect falls back to the shared token or pairing
        }
        await release(opened)
        guard attempt.id == generation else { return } // a connect or disconnect took over while the socket closed
        warnings = regenerated && !reapprovedGateways.contains(lost.key) ? [.deviceIdentityRegenerated] : []
        publish(.error(url: lost.url, cause: reported))
    }

    /// - Returns: Whether `error` is the gateway refusing this device or its credential (not a transport failure).
    private static func isGatewayRefusal(_ error: GatewayError) -> Bool {
        switch error {
        case .pairingRequired, .connectRejected: return true
        default: return false
        }
    }

    /// Ends the live session's ownership synchronously: stops its watcher and clears ``active``.
    ///
    /// - Returns: What the caller must close.
    private func detachActive() -> OpenedResources? {
        watcher?.cancel()
        watcher = nil
        guard let session = active else { return nil }
        active = nil
        return OpenedResources(client: session.client, rpc: session.rpc)
    }

    /// Closes the socket, then the REST client. Neither close throws; they run in a task of their own, so a
    /// cancelled caller cannot cut them short.
    private func release(_ opened: OpenedResources?) async {
        guard let opened, opened.client != nil || opened.rpc != nil else { return }
        let rpc = opened.rpc
        let client = opened.client
        await Task {
            if let rpc { await rpc.close() }
            if let client { await client.close() }
        }.value
    }

    /// - Parameter reconnecting: `true` for the state published while the silent reconnect replaces `session`.
    private static func connectedState(_ session: ActiveSession, reconnecting: Bool = false) -> GatewayConnection {
        .connected(
            url: session.url,
            displayName: session.hello.gatewayHost ?? defaultDisplayName,
            version: session.hello.serverVersion,
            authRequirement: session.hello.authRequirement(token: session.token),
            isHttps: session.url.lowercased().hasPrefix("https://"),
            capabilities: session.hello.capabilities,
            hardwareInfo: nil,
            tokenOrNull: session.token,
            reconnecting: reconnecting,
            sessionGeneration: session.generation
        )
    }

    /// Unclassified failures (encoding, a client bug) are client faults, not connectivity: never `.unreachable`.
    /// Only the type name is kept: an error's text could echo a value.
    private static func typed(_ error: any Error, url: String) -> GatewayError {
        if let gatewayError = error as? GatewayError { return gatewayError }
        return .protocolViolation(url: url, detail: "client error \(type(of: error))")
    }

    // ── Subscriber management ────────────────────────────────────────────────

    private func registerAndReplay(id: UUID, continuation: AsyncStream<GatewayConnection>.Continuation) {
        subscribers[id] = continuation
        continuation.yield(state)
    }

    private func unregister(id: UUID) {
        subscribers.removeValue(forKey: id)
    }

    /// Writes the snapshot box first, so a racing synchronous read sees the new value before any subscriber.
    private func publish(_ next: GatewayConnection) {
        state = next
        snapshotBox.set(next)
        for continuation in subscribers.values {
            continuation.yield(next)
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

    /// Synchronous mailbox for ``currentState`` (the protocol requires a non-async read).
    private let snapshotBox = SnapshotBox()
}

// ── Connect attempt bookkeeping ──────────────────────────────────────────────

/// What one connect attempt has opened so far. Whoever takes a resource out closes it, so each is closed once.
private final class ConnectAttempt {
    let id: UInt64
    private(set) var client: OpenClawAPIClient?
    private(set) var rpc: GatewayRPCClient?

    init(id: UInt64) { self.id = id }

    func hold(_ client: OpenClawAPIClient) { self.client = client }
    func hold(_ rpc: GatewayRPCClient) { self.rpc = rpc }

    /// - Returns: The resources still held; the attempt holds nothing afterwards.
    func take() -> OpenedResources {
        defer {
            client = nil
            rpc = nil
        }
        return OpenedResources(client: client, rpc: rpc)
    }
}

/// Resources to close.
private struct OpenedResources {
    let client: OpenClawAPIClient?
    let rpc: GatewayRPCClient?
}

/// Thrown inside a connect that a newer `connect` or `disconnect` replaced.
private struct SupersededAttempt: Error {}

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
/// ``GatewayConnectionManagerImpl/init(identityProvider:deviceTokens:clientInfo:clientFactory:socketFactory:settingsRepository:challengeTimeout:clock:)``.
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
