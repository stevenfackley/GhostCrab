import Foundation

/// One gateway control-plane socket (ADR-0007). Request ids are `gc-1`, `gc-2`, …; each request waits for the
/// `res` frame with its id. `connect.challenge` is kept as soon as it arrives, so a late caller still gets it.
///
/// Every request is bounded by its own timeout, which alone becomes ``GatewayError/timeout(url:underlying:)``; a
/// caller's own timeout or cancellation propagates as `CancellationError`, and the request is forgotten either way.
/// Once the socket ends, pending and later requests fail with the end cause instead of hanging:
/// ``GatewayError/connectionClosed(url:closeCode:reason:)`` for a gateway policy close (1008) with no response
/// explaining it, ``GatewayError/unreachable(url:underlying:)`` for a drop, a transport error or ``close()``.
///
/// Call ``start()`` once, ``close()`` at the end.
public actor GatewayRPCClient {

    /// First frame of every gateway socket.
    public static let connectChallengeEvent = "connect.challenge"

    /// WebSocket close code the gateway uses to refuse a socket (RFC 6455 §7.4.1).
    static let policyViolationCloseCode = 1008

    /// Server events kept for a subscriber that falls behind; older ones are dropped.
    static let eventBufferSize = 64

    /// The `ws(s)://…/ws` endpoint, for error messages.
    public nonisolated let endpoint: String

    private let socket: any GatewayWebSocket
    private let defaultTimeout: Duration
    private var nextId = 1
    private var pending: [String: CheckedContinuation<GatewayResponseFrame, any Error>] = [:]
    private var timers: [String: Task<Void, Never>] = [:]
    private var challenge: GatewayEventFrame?
    private var challengeWaiters: [UUID: CheckedContinuation<GatewayEventFrame, any Error>] = [:]
    private var challengeTimers: [UUID: Task<Void, Never>] = [:]
    private var subscribers: [UUID: AsyncStream<GatewayEventFrame>.Continuation] = [:]
    private var closedError: GatewayError?
    private var readerTask: Task<Void, Never>?

    /// - Parameters:
    ///   - socket: An open socket; the client owns it from now on.
    ///   - endpoint: The socket URL, for error messages.
    ///   - defaultTimeout: Per-request timeout.
    public init(socket: any GatewayWebSocket, endpoint: String, defaultTimeout: Duration = .seconds(30)) {
        self.socket = socket
        self.endpoint = endpoint
        self.defaultTimeout = defaultTimeout
    }

    /// `true` once the socket has ended or ``close()`` was called.
    public var isClosed: Bool { closedError != nil }

    /// Requests still waiting for a response. Visible for tests.
    var pendingRequestCount: Int { pending.count }

    /// Starts the reader loop. Idempotent. Requests sent before this never see their response.
    public func start() {
        guard readerTask == nil else { return }
        readerTask = Task { await self.readLoop() }
    }

    private func readLoop() async {
        var failure: (any Error)?
        do {
            while let text = try await socket.receive() {
                dispatch(text)
            }
        } catch {
            failure = error
        }
        guard closedError == nil else { return } // close() already ended the session
        let peerClose = await socket.closeFrame()
        finish(with: endCause(peerClose: peerClose, failure: failure))
    }

    private func endCause(peerClose: GatewaySocketClose?, failure: (any Error)?) -> GatewayError {
        if let peerClose, peerClose.code == Self.policyViolationCloseCode {
            return .connectionClosed(url: endpoint, closeCode: peerClose.code, reason: peerClose.reason)
        }
        return .unreachable(url: endpoint, underlying: failure)
    }

    private func dispatch(_ text: String) {
        switch GatewayFrameCodec.decode(text) {
        case .response(let response)?:
            // An id nobody waits for (answered late, after a timeout or cancellation) is dropped.
            timers.removeValue(forKey: response.id)?.cancel()
            pending.removeValue(forKey: response.id)?.resume(returning: response)
        case .event(let event)?:
            if event.event == Self.connectChallengeEvent, challenge == nil {
                challenge = event
                challengeTimers.values.forEach { $0.cancel() }
                challengeTimers.removeAll()
                let waiters = Array(challengeWaiters.values)
                challengeWaiters.removeAll()
                waiters.forEach { $0.resume(returning: event) }
            }
            subscribers.values.forEach { $0.yield(event) }
        case nil:
            break // not a protocol-4 frame: ignored, like the official clients do
        }
    }

    /// - Parameter timeout: How long to wait for the gateway's first frame (the real one sends it within ~2 ms).
    /// - Returns: The `connect.challenge` event, also when it arrived before this call.
    /// - Throws: ``GatewayError/timeout(url:underlying:)`` when none arrives within `timeout`; the end cause (see
    ///   the type's documentation) when the socket ended first; `CancellationError` when the caller is cancelled
    ///   (its own timeout included).
    public func awaitChallenge(timeout: Duration = .seconds(10)) async throws -> GatewayEventFrame {
        if let challenge { return challenge }
        if let closedError { throw closedError }
        try Task.checkCancellation()
        let waiterId = UUID()
        let label = "\(endpoint)#\(Self.connectChallengeEvent)"
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<GatewayEventFrame, any Error>) in
                challengeWaiters[waiterId] = continuation
                challengeTimers[waiterId] = Task {
                    do { try await Task.sleep(for: timeout) } catch { return }
                    await self.failChallengeWaiter(waiterId, with: GatewayError.timeout(url: label, underlying: nil))
                }
            }
        } onCancel: {
            Task { await self.failChallengeWaiter(waiterId, with: CancellationError()) }
        }
    }

    private func failChallengeWaiter(_ id: UUID, with error: any Error) {
        challengeTimers.removeValue(forKey: id)?.cancel()
        challengeWaiters.removeValue(forKey: id)?.resume(throwing: error)
    }

    /// Sends a request and waits for its response.
    ///
    /// - Parameters:
    ///   - method: RPC method, e.g. `skills.status`.
    ///   - params: Encodable params (``EmptyParams`` for `{}`).
    ///   - timeout: Overrides the default per-request timeout.
    /// - Returns: The response `payload`, if any.
    /// - Throws: The mapped ``GatewayError`` for `ok:false` (see `GatewayErrorShape.toGatewayError`);
    ///   ``GatewayError/timeout(url:underlying:)`` (url `<endpoint>#<method>`) when this request's own timeout
    ///   expires; ``GatewayError/unreachable(url:underlying:)`` carrying the cause when the frame could not be sent;
    ///   the end cause when the socket ended (see the type's documentation); `CancellationError` when the caller is
    ///   cancelled (its own timeout included) — never a gateway timeout for that; `EncodingError` for params that
    ///   cannot be encoded.
    public func request<P: Encodable & Sendable>(_ method: String, params: P, timeout: Duration? = nil) async throws -> AnyCodable? {
        if let closedError { throw closedError }
        try Task.checkCancellation()
        let id = "gc-\(nextId)"
        nextId += 1
        let text = try GatewayFrameCodec.encodeRequest(id: id, method: method, params: params)
        let limit = timeout ?? defaultTimeout
        let label = "\(endpoint)#\(method)"
        let response: GatewayResponseFrame = try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<GatewayResponseFrame, any Error>) in
                // Registered before the frame goes out, so an instant response always finds its waiter.
                pending[id] = continuation
                timers[id] = Task {
                    do { try await Task.sleep(for: limit) } catch { return }
                    await self.fail(id, with: GatewayError.timeout(url: label, underlying: nil))
                }
                Task { await self.send(text, id: id) }
            }
        } onCancel: {
            Task { await self.fail(id, with: CancellationError()) }
        }
        guard response.ok else {
            let shape = response.error ?? GatewayErrorShape(code: "UNKNOWN", message: "ok:false without an error object")
            throw shape.toGatewayError(url: endpoint, method: method)
        }
        return response.payload
    }

    /// `request(_:params:timeout:)` with `{}` params.
    public func request(_ method: String, timeout: Duration? = nil) async throws -> AnyCodable? {
        try await request(method, params: EmptyParams(), timeout: timeout)
    }

    private func send(_ text: String, id: String) async {
        guard pending[id] != nil else { return } // cancelled, timed out or closed before it went out
        do {
            try await socket.send(text)
        } catch {
            fail(id, with: GatewayError.unreachable(url: endpoint, underlying: error))
        }
    }

    private func fail(_ id: String, with error: any Error) {
        timers.removeValue(forKey: id)?.cancel()
        pending.removeValue(forKey: id)?.resume(throwing: error)
    }

    /// Server events (`tick`, `health`, `config.changed`, …). Each call returns an independent stream. Best effort:
    /// a subscriber that falls behind loses the oldest beyond 64 buffered events. The stream finishes when the
    /// socket ends or ``close()`` is called (at once when that already happened).
    ///
    /// Nothing is replayed: a stream sees only events that arrive after this call. A consumer waiting for an event
    /// its own request triggers (e.g. `config.changed` after `skills.update`, which can even precede the response)
    /// must subscribe before sending that request.
    public func events() -> AsyncStream<GatewayEventFrame> {
        let (stream, continuation) = AsyncStream.makeStream(
            of: GatewayEventFrame.self,
            bufferingPolicy: .bufferingNewest(Self.eventBufferSize)
        )
        guard closedError == nil else {
            continuation.finish()
            return stream
        }
        let id = UUID()
        subscribers[id] = continuation
        continuation.onTermination = { _ in Task { await self.removeSubscriber(id) } }
        return stream
    }

    private func removeSubscriber(_ id: UUID) {
        subscribers.removeValue(forKey: id)
    }

    /// Fails every pending request and ``awaitChallenge(timeout:)`` with ``GatewayError/unreachable(url:underlying:)``,
    /// finishes every ``events()`` stream, stops the reader and closes the socket. Idempotent.
    public func close() async {
        finish(with: .unreachable(url: endpoint, underlying: nil))
        readerTask?.cancel()
        await socket.close()
    }

    /// Records the first end cause and fails everything still waiting with it. Later calls change nothing.
    private func finish(with error: GatewayError) {
        guard closedError == nil else { return }
        closedError = error
        timers.values.forEach { $0.cancel() }
        timers.removeAll()
        let waiting = Array(pending.values)
        pending.removeAll()
        waiting.forEach { $0.resume(throwing: error) }
        challengeTimers.values.forEach { $0.cancel() }
        challengeTimers.removeAll()
        let challengeWaiting = Array(challengeWaiters.values)
        challengeWaiters.removeAll()
        challengeWaiting.forEach { $0.resume(throwing: error) }
        let streams = Array(subscribers.values)
        subscribers.removeAll()
        streams.forEach { $0.finish() }
    }
}
