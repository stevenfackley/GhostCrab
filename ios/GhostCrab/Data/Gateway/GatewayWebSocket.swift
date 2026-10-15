import Foundation

/// The close frame a peer sent: WebSocket close code and reason (RFC 6455 §7.4).
public struct GatewaySocketClose: Sendable, Equatable {
    /// WebSocket close code, e.g. `1008` (policy violation) after a refused `connect`.
    public let code: Int
    /// The close reason; may be empty.
    public let reason: String

    public init(code: Int, reason: String) {
        self.code = code
        self.reason = reason
    }
}

/// Transport seam for the gateway control-plane socket. Production: ``URLSessionGatewayWebSocket``;
/// tests: a scripted actor.
public protocol GatewayWebSocket: Sendable {
    /// Sends one text frame.
    ///
    /// - Parameter text: The frame.
    /// - Throws: The transport error when the frame could not be sent.
    func send(_ text: String) async throws
    /// - Returns: The next text frame, or `nil` once the socket has closed in an orderly way.
    /// - Throws: The transport error when the socket failed; ``UndecodableGatewayFrame`` for a frame that cannot be
    ///   read as text (never `nil`: that is not an orderly close).
    func receive() async throws -> String?
    /// - Returns: The close frame the gateway sent, once ``receive()`` has ended; `nil` when the socket ended
    ///   without one (a dropped connection). May wait briefly for a close frame the transport reports late.
    func closeFrame() async -> GatewaySocketClose?
    /// Closes the socket. Idempotent.
    func close() async
}

/// A frame a ``GatewayWebSocket`` could not read as text: a binary frame that is not UTF-8, or a message kind the
/// SDK added later. Names the kind only, never the bytes. ``GatewayRPCClient`` ends the session with
/// ``GatewayError/protocolViolation(url:detail:)`` for it.
public struct UndecodableGatewayFrame: Error, Sendable, Equatable {
    /// What arrived, e.g. `binary frame that is not UTF-8`.
    public let kind: String

    public init(kind: String) {
        self.kind = kind
    }
}

/// Builds sockets; injectable into ``GatewayConnectionManagerImpl``.
public protocol GatewaySocketFactory: Sendable {
    /// - Parameter url: `ws(s)://host:port/ws`.
    /// - Returns: A socket that is already connecting.
    func makeSocket(url: URL) -> any GatewayWebSocket
}

/// ``GatewaySocketFactory`` producing ``URLSessionGatewayWebSocket``s.
public struct URLSessionGatewaySocketFactory: GatewaySocketFactory {
    public init() {}
    public func makeSocket(url: URL) -> any GatewayWebSocket { URLSessionGatewayWebSocket(url: url) }
}

/// ``GatewayWebSocket`` over `URLSessionWebSocketTask`. ATS applies to `ws://` exactly as to `http://`
/// (ADR-0006); the app's own public-IP cleartext policy is enforced before the socket is built
/// (``GatewayWebSocketURL``), because custom `URLProtocol`s never see WebSocket tasks.
///
/// `Sendable` without `@unchecked`: both stored properties are immutable and the URLSession types are `Sendable`.
public final class URLSessionGatewayWebSocket: GatewayWebSocket {

    /// How long ``closeFrame()`` waits for a close frame that URLSession records after failing `receive()`.
    private static let closeFrameWait: Duration = .seconds(1)
    private static let closeFramePoll: Duration = .milliseconds(50)

    private let session: URLSession
    private let task: URLSessionWebSocketTask

    /// - Parameters:
    ///   - url: `ws(s)://host:port/ws`.
    ///   - timeout: `timeoutIntervalForRequest`, which URLSession also applies as an idle timer between received
    ///     frames. The gateway sends a `tick` every 30 s, so this stays well above that; the handshake itself is
    ///     bounded by ``GatewayHandshake/challengeTimeout`` and ``GatewayHandshake/connectTimeout``.
    public init(url: URL, timeout: TimeInterval = 75) {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = timeout
        configuration.waitsForConnectivity = false
        session = URLSession(configuration: configuration)
        task = session.webSocketTask(with: url)
        task.maximumMessageSize = 32 * 1024 * 1024 // hello-ok.policy.maxPayload is 25 MiB
        task.resume()
    }

    public func send(_ text: String) async throws {
        try await task.send(.string(text))
    }

    public func receive() async throws -> String? {
        let message: URLSessionWebSocketTask.Message
        do {
            message = try await task.receive()
        } catch {
            if task.closeCode != .invalid { return nil } // orderly close (e.g. 1008 after a failed connect)
            throw error
        }
        switch message {
        case .string(let text):
            return text
        case .data(let data):
            guard let text = String(data: data, encoding: .utf8) else {
                throw UndecodableGatewayFrame(kind: "binary frame that is not UTF-8")
            }
            return text
        @unknown default:
            throw UndecodableGatewayFrame(kind: "unknown message kind")
        }
    }

    public func closeFrame() async -> GatewaySocketClose? {
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: Self.closeFrameWait)
        while task.closeCode == .invalid {
            guard clock.now < deadline else { return nil }
            do { try await Task.sleep(for: Self.closeFramePoll) } catch { return nil }
        }
        let reason = task.closeReason.map { String(decoding: $0, as: UTF8.self) } ?? ""
        return GatewaySocketClose(code: task.closeCode.rawValue, reason: reason)
    }

    public func close() async {
        task.cancel(with: .normalClosure, reason: nil)
        session.invalidateAndCancel()
    }
}
