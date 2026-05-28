import Foundation

/// Wire type for a `POST /api/ai/recommend` request body.
///
/// - Parameters:
///   - query: Free-text user query.
///   - context: Auto-collected session context sent for grounding.
public struct AIRecommendationRequestDTO: Codable, Sendable, Equatable {
    public let query: String
    public let context: AIContextDTO

    public init(query: String, context: AIContextDTO) {
        self.query = query
        self.context = context
    }
}

/// Session context serialized alongside the AI query.
///
/// Never includes secrets — tokens are stripped before serialization.
///
/// - Parameters:
///   - activeConfig: Current gateway config as a raw JSON object. `nil` if unavailable.
///   - hardwareInfo: Hardware description from the gateway's `/status` response. May be `nil`.
///   - activeModelId: ID of the currently active model, if known.
public struct AIContextDTO: Codable, Sendable, Equatable {
    public let activeConfig: [String: AnyCodable]?
    public let hardwareInfo: String?
    public let activeModelId: String?

    public init(
        activeConfig: [String: AnyCodable]?,
        hardwareInfo: String?,
        activeModelId: String?
    ) {
        self.activeConfig = activeConfig
        self.hardwareInfo = hardwareInfo
        self.activeModelId = activeModelId
    }

    private enum CodingKeys: String, CodingKey {
        case activeConfig = "active_config"
        case hardwareInfo = "hardware_info"
        case activeModelId = "active_model_id"
    }
}
