import Foundation

/// The full `openclaw.json` configuration as returned by the gateway.
///
/// Sections are kept as raw `AnyCodable` JSON values to avoid brittle schema lock-in.
/// Phase 6 adds typed wrappers for well-known sections (`gateway.http`, `gateway.auth`, etc.)
/// while this type remains the authoritative wire type.
///
/// - Parameters:
///   - sections: Top-level config keys mapped to their raw JSON values.
///   - etag: Server-side version tag for optimistic-concurrency detection. May be `nil`
///     if the gateway does not support ETags.
public struct OpenClawConfig: Sendable {
    public let sections: [String: AnyCodable]
    public let etag: String?

    public init(sections: [String: AnyCodable], etag: String? = nil) {
        self.sections = sections
        self.etag = etag
    }
}
