import Foundation

/// Gateway WebSocket protocol constants (ADR-0007).
public enum GatewayProtocol {
    /// Wire version GhostCrab implements (`connect.minProtocol` = `maxProtocol`).
    public static let version = 4
}

/// `{}` request params.
public struct EmptyParams: Encodable, Sendable, Equatable {

    public init() {}

    /// Encodes an empty JSON object: the gateway expects `params` to be an object, never absent.
    public func encode(to encoder: any Encoder) throws {
        _ = encoder.container(keyedBy: NoKeys.self)
    }

    private struct NoKeys: CodingKey {
        var stringValue: String
        var intValue: Int? { nil }
        init?(stringValue: String) { self.stringValue = stringValue }
        init?(intValue: Int) { return nil }
    }
}

/// Client → gateway request: `{"type":"req","id":"…","method":"…","params":{…}}`.
///
/// `connect` params carry the gateway token and the device proof, so `description`, `debugDescription` and the
/// mirror never print `params`.
struct GatewayRequestFrame<Params: Encodable & Sendable>: Encodable, Sendable,
    CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {

    let type = "req"
    /// Unique per socket; the response echoes it.
    let id: String
    /// RPC method name, e.g. `skills.status`.
    let method: String
    /// Method parameters.
    let params: Params

    var description: String { "GatewayRequestFrame(id: \(id), method: \(method), params: [REDACTED])" }
    var debugDescription: String { description }
    var customMirror: Mirror {
        Mirror(self, children: ["id": id, "method": method, "params": "[REDACTED]"], displayStyle: .struct)
    }
}

/// `error` of a failed response (upstream `ErrorShape`).
public struct GatewayErrorShape: Sendable, Equatable {
    /// Top-level code: `INVALID_REQUEST`, `FORBIDDEN`, `NOT_PAIRED`, `UNAVAILABLE`, ….
    /// ``GatewayFrameCodec/malformedErrorCode`` when the error object had no string `code` and `message`.
    public let code: String
    /// Human-readable server text, shown verbatim.
    public let message: String
    /// Structured details; `details.code` is the machine-readable sub-code.
    public let details: AnyCodable?
    /// Server retry hint (a JSON boolean only); absent means "not retryable".
    public let retryable: Bool?
    /// Server back-off hint (a JSON integer only).
    public let retryAfterMs: Int?

    public init(code: String, message: String, details: AnyCodable? = nil, retryable: Bool? = nil, retryAfterMs: Int? = nil) {
        self.code = code
        self.message = message
        self.details = details
        self.retryable = retryable
        self.retryAfterMs = retryAfterMs
    }

    /// `details` when it is a JSON object, else `nil`.
    public var detailsObject: [String: AnyCodable]? { details?.objectValue }

    /// `details.code` (e.g. `PAIRING_REQUIRED`, `MISSING_SCOPE`, `AUTH_TOKEN_MISMATCH`).
    public var detailCode: String? { detailString("code") }

    /// - Parameter key: A key of `details`.
    /// - Returns: The string at `details[key]`, or `nil` when absent or not a JSON string.
    public func detailString(_ key: String) -> String? {
        detailsObject?[key]?.value as? String
    }

    /// - Parameter key: A key of `details`.
    /// - Returns: The string elements of the array at `details[key]` (others skipped); empty when absent.
    public func detailStrings(_ key: String) -> [String] {
        (detailsObject?[key]?.value as? [AnyCodable])?.compactMap { $0.value as? String } ?? []
    }
}

/// Answer to a request. `hello-ok` carries the issued device token, so `description`, `debugDescription` and the
/// mirror never print `payload`.
public struct GatewayResponseFrame: Sendable, CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    /// The request id it answers.
    public let id: String
    /// `true` on success.
    public let ok: Bool
    /// Result; may also be present on failure (e.g. `skills.install`).
    public let payload: AnyCodable?
    /// Present when `ok` is `false` and the gateway sent an error object.
    public let error: GatewayErrorShape?

    public var description: String {
        "GatewayResponseFrame(id: \(id), ok: \(ok), payload: \(redacted(payload)), error: \(error.map { "\($0.code)" } ?? "nil"))"
    }
    public var debugDescription: String { description }
    public var customMirror: Mirror {
        Mirror(
            self,
            children: ["id": id, "ok": ok, "payload": redacted(payload), "error": error.map { "\($0.code)" } ?? "nil"],
            displayStyle: .struct
        )
    }
}

/// Server push (`connect.challenge`, `tick`, `health`, `config.changed`, …). Event bodies are never printed.
public struct GatewayEventFrame: Sendable, CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    /// Event name.
    public let event: String
    /// Event body.
    public let payload: AnyCodable?
    /// Connection sequence number, when the server sends one.
    public let seq: Int?

    public var description: String {
        "GatewayEventFrame(event: \(event), seq: \(seq.map(String.init) ?? "nil"), payload: \(redacted(payload)))"
    }
    public var debugDescription: String { description }
    public var customMirror: Mirror {
        Mirror(
            self,
            children: ["event": event, "seq": seq.map(String.init) ?? "nil", "payload": redacted(payload)],
            displayStyle: .struct
        )
    }
}

/// A decoded gateway → client frame.
public enum GatewayFrame: Sendable, CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    case response(GatewayResponseFrame)
    case event(GatewayEventFrame)

    public var description: String {
        switch self {
        case .response(let response): return response.description
        case .event(let event): return event.description
        }
    }
    public var debugDescription: String { description }
    public var customMirror: Mirror {
        switch self {
        case .response(let response): return Mirror(self, children: ["response": response], displayStyle: .enum)
        case .event(let event): return Mirror(self, children: ["event": event], displayStyle: .enum)
        }
    }
}

/// Placeholder for a frame body in descriptions: bodies can carry tokens and device proofs.
private func redacted(_ body: AnyCodable?) -> String {
    body == nil ? "nil" : "[REDACTED]"
}

/// Encodes requests and decodes everything the gateway sends.
public enum GatewayFrameCodec {

    /// ``GatewayErrorShape/code`` of an `error` object this client could not read.
    public static let malformedErrorCode = "MALFORMED_ERROR"

    /// - Parameter text: One text frame from the socket.
    /// - Returns: The frame, or `nil` for anything that is not a well-formed protocol-4 `res` / `event` frame
    ///   (malformed JSON, JSON-RPC, numeric ids, `ok` that is not a JSON boolean). The official clients ignore such
    ///   frames too; never throws. Inside `error`, `code` and `message` are required strings and the optional
    ///   fields are read leniently, so a malformed hint never hides the error's code or details.
    public static func decode(_ text: String) -> GatewayFrame? {
        guard let root = try? JSONDecoder().decode(AnyCodable.self, from: Data(text.utf8)),
              let object = root.objectValue else { return nil }
        switch object["type"]?.value as? String {
        case "res":
            guard let id = object["id"]?.value as? String, let ok = object["ok"]?.value as? Bool else { return nil }
            let error = object["error"]?.objectValue.map(decodeError)
            return .response(GatewayResponseFrame(id: id, ok: ok, payload: nonNull(object["payload"]), error: error))
        case "event":
            guard let name = object["event"]?.value as? String else { return nil }
            let seq = object["seq"]?.value as? Int
            return .event(GatewayEventFrame(event: name, payload: nonNull(object["payload"]), seq: seq))
        default:
            return nil
        }
    }

    /// - Parameters:
    ///   - id: Unique per socket.
    ///   - method: RPC method name.
    ///   - params: Encodable params (``EmptyParams`` for `{}`).
    /// - Returns: The request frame as JSON text.
    /// - Throws: `EncodingError` when `params` cannot be encoded.
    static func encodeRequest<P: Encodable & Sendable>(id: String, method: String, params: P) throws -> String {
        let data = try JSONEncoder().encode(GatewayRequestFrame(id: id, method: method, params: params))
        return String(decoding: data, as: UTF8.self)
    }

    private static func decodeError(_ object: [String: AnyCodable]) -> GatewayErrorShape {
        guard let code = object["code"]?.value as? String, let message = object["message"]?.value as? String else {
            // Never echo the raw object: it is server-controlled and may be large or sensitive.
            return GatewayErrorShape(code: malformedErrorCode, message: "error object without a string code and message")
        }
        return GatewayErrorShape(
            code: code,
            message: message,
            details: nonNull(object["details"]),
            retryable: object["retryable"]?.value as? Bool,
            retryAfterMs: object["retryAfterMs"]?.value as? Int
        )
    }

    /// `value`, unless it is absent or JSON `null`.
    private static func nonNull(_ value: AnyCodable?) -> AnyCodable? {
        guard let value, value.value != nil else { return nil }
        return value
    }
}

extension AnyCodable {
    /// The value as a JSON object, or `nil`.
    var objectValue: [String: AnyCodable]? { value as? [String: AnyCodable] }

    /// - Parameter path: Keys to follow from this object.
    /// - Returns: The nested object at `path`, or `nil` as soon as a step is missing or not an object.
    func object(at path: String...) -> [String: AnyCodable]? {
        path.reduce(objectValue) { current, key in current?[key]?.objectValue }
    }

    /// Re-decodes this JSON value as `T` (frames are decoded once as `AnyCodable`, then typed per method).
    ///
    /// - Parameter type: The target type.
    /// - Returns: The decoded value.
    /// - Throws: `DecodingError` when the JSON does not match `T`; `EncodingError` for a value that is not JSON.
    func decoded<T: Decodable>(as type: T.Type) throws -> T {
        try JSONDecoder().decode(T.self, from: JSONEncoder().encode(self))
    }
}
