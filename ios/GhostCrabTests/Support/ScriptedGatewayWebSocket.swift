import Foundation
@testable import GhostCrab

/// Fake gateway socket. Emits the captured `connect.challenge` first (unless `challenge` is false), then answers
/// each request with the next queued frame for its method, its `id` rewritten to the request's id. The last queued
/// frame of a method repeats. A method without frames gets no answer (``push(_:)`` can answer it later).
/// `eventsBeforeReply` frames are pushed right before a method's reply (the real gateway's `config.changed` can
/// precede the response of the write that caused it). Methods in `closeAfterReplyTo` end the socket after the reply
/// with close 1008 and the reply's error message as the reason, as the real gateway does after a failed `connect`.
/// `failSendsWith` makes every `send` throw that error.
actor ScriptedGatewayWebSocket: GatewayWebSocket {

    /// WebSocket policy-violation close code.
    static let policyViolation = 1008

    private var inbox: [Result<String?, any Error>]
    private var waiter: CheckedContinuation<String?, any Error>?
    private var replies: [String: [String]]
    private let eventsBefore: [String: [String]]
    private let closeAfter: Set<String>
    private let sendFailure: (any Error)?
    private var sentFrames: [String] = []
    private var peerClose: GatewaySocketClose?

    /// `true` once the client called `close()`.
    private(set) var closedByClient = false

    /// How many times the client called `close()` (each owner must close exactly once).
    private(set) var closeCount = 0

    init(
        challenge: Bool = true,
        replies: [String: [String]] = [:],
        eventsBeforeReply: [String: [String]] = [:],
        closeAfterReplyTo: Set<String> = [],
        failSendsWith sendFailure: (any Error)? = nil
    ) throws {
        self.inbox = challenge ? [.success(try GatewayFixtures.text("challenge"))] : []
        self.replies = replies
        self.eventsBefore = eventsBeforeReply
        self.closeAfter = closeAfterReplyTo
        self.sendFailure = sendFailure
    }

    /// The raw text of the first request the client sent for `method`.
    func sentRequest(_ method: String) -> String? {
        sentRequests(method).first
    }

    /// The raw text of every request the client sent for `method`, in order.
    func sentRequests(_ method: String) -> [String] {
        sentFrames.filter { Self.field("method", of: $0) == method }
    }

    /// The `id` of the first request the client sent for `method`.
    func sentId(_ method: String) -> String? {
        sentRequest(method).flatMap { Self.field("id", of: $0) }
    }

    /// Methods the client called, in order.
    func sentMethods() -> [String] {
        sentFrames.compactMap { Self.field("method", of: $0) }
    }

    func send(_ text: String) async throws {
        if let sendFailure { throw sendFailure }
        sentFrames.append(text)
        guard let id = Self.field("id", of: text),
              let method = Self.field("method", of: text),
              var queue = replies[method], let reply = queue.first
        else { return }
        if queue.count > 1 {
            queue.removeFirst()
            replies[method] = queue
        }
        eventsBefore[method]?.forEach { deliver(.success($0)) }
        deliver(.success(try Self.rewritingId(reply, to: id)))
        if closeAfter.contains(method) {
            drop(closeCode: Self.policyViolation, reason: Self.errorMessage(of: reply) ?? "")
        }
    }

    func receive() async throws -> String? {
        if !inbox.isEmpty { return try inbox.removeFirst().get() }
        return try await withCheckedThrowingContinuation { waiter = $0 }
    }

    func closeFrame() async -> GatewaySocketClose? {
        peerClose
    }

    func close() async {
        closedByClient = true
        closeCount += 1
        deliver(.success(nil))
    }

    /// Delivers a server-pushed frame.
    func push(_ text: String) { deliver(.success(text)) }

    /// Simulates the gateway ending the socket, with a close frame when `closeCode` is set.
    func drop(closeCode: Int? = nil, reason: String = "") {
        if let closeCode { peerClose = GatewaySocketClose(code: closeCode, reason: reason) }
        deliver(.success(nil))
    }

    /// Simulates a transport failure: the client's pending `receive()` throws `error`.
    func failTransport(_ error: any Error) { deliver(.failure(error)) }

    private func deliver(_ next: Result<String?, any Error>) {
        if let waiting = waiter {
            waiter = nil
            waiting.resume(with: next)
        } else {
            inbox.append(next)
        }
    }

    private static func field(_ key: String, of frame: String) -> String? {
        (try? JSONSerialization.jsonObject(with: Data(frame.utf8)) as? [String: Any])?[key] as? String
    }

    private static func errorMessage(of frame: String) -> String? {
        ((try? JSONSerialization.jsonObject(with: Data(frame.utf8)) as? [String: Any])?["error"] as? [String: Any])?["message"] as? String
    }

    private static func rewritingId(_ frame: String, to id: String) throws -> String {
        var object = try JSONSerialization.jsonObject(with: Data(frame.utf8)) as? [String: Any] ?? [:]
        object["id"] = id
        return String(decoding: try JSONSerialization.data(withJSONObject: object), as: UTF8.self)
    }
}

extension Dictionary where Key == String, Value == [String] {
    /// Scripted replies from fixture names by method, e.g. `.fixtures(["connect": "hello-ok-paired"])`.
    static func fixtures(_ byMethod: [String: String]) throws -> [String: [String]] {
        try byMethod.mapValues { [try GatewayFixtures.text($0)] }
    }
}

/// ``GatewaySocketFactory`` that hands out prepared sockets in order (the last one repeats).
final class FixedSocketFactory: GatewaySocketFactory, @unchecked Sendable {
    private let lock = NSLock()
    private var sockets: [ScriptedGatewayWebSocket]

    init(_ sockets: ScriptedGatewayWebSocket...) { self.sockets = sockets }

    init(sockets: [ScriptedGatewayWebSocket]) { self.sockets = sockets }

    func makeSocket(url: URL) -> any GatewayWebSocket {
        lock.withLock { sockets.count > 1 ? sockets.removeFirst() : sockets[0] }
    }
}
