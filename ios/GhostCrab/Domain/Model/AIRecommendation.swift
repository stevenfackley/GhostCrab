import Foundation

/// A recommendation returned by the AI skill on the gateway.
///
/// - Parameters:
///   - query: The original user query.
///   - recommendation: Free-text recommendation from the AI.
///   - suggestedChanges: Structured config changes the AI suggests applying. May be empty.
public struct AIRecommendation: Codable, Sendable, Hashable {
    public let query: String
    public let recommendation: String
    public let suggestedChanges: [SuggestedChange]

    public init(
        query: String,
        recommendation: String,
        suggestedChanges: [SuggestedChange]
    ) {
        self.query = query
        self.recommendation = recommendation
        self.suggestedChanges = suggestedChanges
    }
}

/// A single config change suggested by the AI.
///
/// - Parameters:
///   - section: Top-level config section key (e.g. `"models"`).
///   - key: Nested key within the section.
///   - currentValue: Current value as a string representation, or `nil` if unknown.
///   - suggestedValue: Proposed new value as a string representation.
///   - rationale: Short explanation for the change. Empty string when the gateway omits
///     a rationale. Never `nil` — use `isEmpty` to test.
public struct SuggestedChange: Codable, Sendable, Hashable {
    public let section: String
    public let key: String
    public let currentValue: String?
    public let suggestedValue: String
    public let rationale: String

    public init(
        section: String,
        key: String,
        currentValue: String?,
        suggestedValue: String,
        rationale: String = ""
    ) {
        self.section = section
        self.key = key
        self.currentValue = currentValue
        self.suggestedValue = suggestedValue
        self.rationale = rationale
    }
}
