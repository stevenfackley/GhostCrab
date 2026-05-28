import Foundation

/// Wire-type DTO for a single model entry returned by `GET /api/models/status`.
///
/// All fields except `id` and `provider` are optional with sensible defaults so
/// the client tolerates partially-populated gateway responses.
///
/// - Parameters:
///   - id: Unique model identifier (e.g. `"gpt-4o"`).
///   - provider: Provider slug (e.g. `"openai"`, `"anthropic"`).
///   - displayName: Human-readable label. Defaults to `id` when absent.
///   - isActive: Whether this model is currently the active/default model.
///   - status: Lifecycle status string: `"ready"`, `"auth-error"`, `"loading"`, etc.
///   - capabilities: List of capability tags reported by the gateway.
public struct ModelDTO: Codable, Sendable, Equatable {
    public let id: String
    public let provider: String
    public let displayName: String
    public let isActive: Bool
    public let status: String
    public let capabilities: [String]

    public init(
        id: String,
        provider: String,
        displayName: String? = nil,
        isActive: Bool = false,
        status: String = "unknown",
        capabilities: [String] = []
    ) {
        self.id = id
        self.provider = provider
        self.displayName = displayName ?? id
        self.isActive = isActive
        self.status = status
        self.capabilities = capabilities
    }

    private enum CodingKeys: String, CodingKey {
        case id, provider, displayName, isActive, status, capabilities
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let id = try c.decode(String.self, forKey: .id)
        self.id = id
        self.provider = try c.decode(String.self, forKey: .provider)
        self.displayName = (try? c.decode(String.self, forKey: .displayName)) ?? id
        self.isActive = (try? c.decode(Bool.self, forKey: .isActive)) ?? false
        self.status = (try? c.decode(String.self, forKey: .status)) ?? "unknown"
        self.capabilities = (try? c.decode([String].self, forKey: .capabilities)) ?? []
    }
}
