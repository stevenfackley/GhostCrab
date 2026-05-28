import Foundation

/// A type-erased JSON value that round-trips through `Codable`.
///
/// Mirrors `kotlinx.serialization.json.JsonElement` — used to carry raw config
/// sections (`OpenClawConfig.sections`) and `PATCH /config/{section}` payloads
/// without locking the client into a fixed config schema.
///
/// Supported underlying types:
/// - `String`
/// - `Int` (encoded as JSON number)
/// - `Double` (encoded as JSON number)
/// - `Bool`
/// - `[AnyCodable]` (JSON array)
/// - `[String: AnyCodable]` (JSON object)
/// - `nil` (JSON null)
public struct AnyCodable: Codable, Sendable, Equatable {

    /// The underlying value. May be `nil` for JSON `null`.
    public let value: (any Sendable)?

    public init(_ value: (any Sendable)?) {
        self.value = value
    }

    // MARK: - Decodable

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self.value = nil
        } else if let b = try? container.decode(Bool.self) {
            self.value = b
        } else if let i = try? container.decode(Int.self) {
            self.value = i
        } else if let d = try? container.decode(Double.self) {
            self.value = d
        } else if let s = try? container.decode(String.self) {
            self.value = s
        } else if let arr = try? container.decode([AnyCodable].self) {
            self.value = arr
        } else if let obj = try? container.decode([String: AnyCodable].self) {
            self.value = obj
        } else {
            throw DecodingError.dataCorruptedError(
                in: container,
                debugDescription: "AnyCodable: unsupported JSON value"
            )
        }
    }

    // MARK: - Encodable

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        guard let value else {
            try container.encodeNil()
            return
        }
        switch value {
        case let b as Bool: try container.encode(b)
        case let i as Int: try container.encode(i)
        case let d as Double: try container.encode(d)
        case let s as String: try container.encode(s)
        case let arr as [AnyCodable]: try container.encode(arr)
        case let obj as [String: AnyCodable]: try container.encode(obj)
        case let arr as [Any]: try container.encode(arr.map { AnyCodable($0 as? any Sendable) })
        case let obj as [String: Any]:
            try container.encode(obj.mapValues { AnyCodable($0 as? any Sendable) })
        default:
            throw EncodingError.invalidValue(
                value,
                EncodingError.Context(
                    codingPath: container.codingPath,
                    debugDescription: "AnyCodable: unsupported value type \(type(of: value))"
                )
            )
        }
    }

    // MARK: - Equatable

    public static func == (lhs: AnyCodable, rhs: AnyCodable) -> Bool {
        switch (lhs.value, rhs.value) {
        case (nil, nil): return true
        case (nil, _), (_, nil): return false
        case let (a as Bool, b as Bool): return a == b
        case let (a as Int, b as Int): return a == b
        case let (a as Double, b as Double): return a == b
        case let (a as String, b as String): return a == b
        case let (a as [AnyCodable], b as [AnyCodable]): return a == b
        case let (a as [String: AnyCodable], b as [String: AnyCodable]): return a == b
        default: return false
        }
    }
}
