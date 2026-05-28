import Foundation

/// Wire-type DTO for `GET /status`.
///
/// All fields have defaults so the client can synthesize a "minimal but valid"
/// response when the upstream gateway serves HTML instead of JSON.
public struct StatusResponse: Codable, Sendable, Equatable {
    public let displayName: String
    public let version: String
    public let capabilities: [String]
    public let hardware: String?

    public init(
        displayName: String = "OpenClaw Gateway",
        version: String = "unknown",
        capabilities: [String] = [],
        hardware: String? = nil
    ) {
        self.displayName = displayName
        self.version = version
        self.capabilities = capabilities
        self.hardware = hardware
    }

    private enum CodingKeys: String, CodingKey {
        case displayName, version, capabilities, hardware
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.displayName = (try? c.decode(String.self, forKey: .displayName)) ?? "OpenClaw Gateway"
        self.version = (try? c.decode(String.self, forKey: .version)) ?? "unknown"
        self.capabilities = (try? c.decode([String].self, forKey: .capabilities)) ?? []
        self.hardware = try? c.decode(String.self, forKey: .hardware)
    }
}
