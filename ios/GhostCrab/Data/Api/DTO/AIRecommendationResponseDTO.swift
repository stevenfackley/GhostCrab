import Foundation

/// Wire type for a `POST /api/ai/recommend` response body.
///
/// - Parameters:
///   - recommendation: Free-text recommendation from the gateway's AI skill.
///   - suggestedChanges: Structured config changes the AI suggests. May be empty.
public struct AIRecommendationResponseDTO: Codable, Sendable, Equatable {
    public let recommendation: String
    public let suggestedChanges: [SuggestedChangeDTO]

    public init(recommendation: String, suggestedChanges: [SuggestedChangeDTO] = []) {
        self.recommendation = recommendation
        self.suggestedChanges = suggestedChanges
    }

    private enum CodingKeys: String, CodingKey {
        case recommendation
        case suggestedChanges = "suggested_changes"
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.recommendation = try c.decode(String.self, forKey: .recommendation)
        self.suggestedChanges = (try? c.decode([SuggestedChangeDTO].self, forKey: .suggestedChanges)) ?? []
    }
}

/// A single structured config change suggested by the AI.
///
/// - Parameters:
///   - section: Top-level config section key (e.g. `"models"`).
///   - key: Nested key within the section (e.g. `"timeout_ms"`).
///   - currentValue: Current value as a JSON string representation. `nil` if unknown.
///   - suggestedValue: Proposed new value as a JSON-encoded string.
///   - rationale: Short explanation for the change.
public struct SuggestedChangeDTO: Codable, Sendable, Equatable {
    public let section: String
    public let key: String
    public let currentValue: String?
    public let suggestedValue: String
    public let rationale: String

    public init(
        section: String,
        key: String,
        currentValue: String? = nil,
        suggestedValue: String,
        rationale: String = ""
    ) {
        self.section = section
        self.key = key
        self.currentValue = currentValue
        self.suggestedValue = suggestedValue
        self.rationale = rationale
    }

    private enum CodingKeys: String, CodingKey {
        case section, key
        case currentValue = "current_value"
        case suggestedValue = "suggested_value"
        case rationale
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.section = try c.decode(String.self, forKey: .section)
        self.key = try c.decode(String.self, forKey: .key)
        self.currentValue = try? c.decode(String.self, forKey: .currentValue)
        self.suggestedValue = try c.decode(String.self, forKey: .suggestedValue)
        self.rationale = (try? c.decode(String.self, forKey: .rationale)) ?? ""
    }
}
