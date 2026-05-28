import Foundation

/// Submits AI recommendation queries through the gateway's proxied CLI skill.
///
/// The gateway must have the `skill-ai-recommend` capability; check `isAvailable()` first.
///
/// **Contract frozen at v1.0.**
public protocol AIRecommendationService: Sendable {

    /// Returns whether the connected gateway has the AI recommendation skill installed.
    ///
    /// Probes the gateway's capability list; does not make an AI call.
    ///
    /// - Returns: `true` if the skill is present and responding.
    func isAvailable() async -> Bool

    /// Submits a recommendation query to the gateway's AI skill.
    ///
    /// The `context` is auto-collected and attached to the request body. It never contains
    /// secrets (tokens are stripped before serialization).
    ///
    /// - Parameters:
    ///   - query: Free-text user query (e.g. `"best coding model for 16GB RAM"`).
    ///   - context: Current session context for grounding the recommendation.
    /// - Returns: The `AIRecommendation` containing the response and optional suggested changes.
    /// - Throws: `GatewayError.aiServiceUnavailable` if the skill is not present on the gateway.
    /// - Throws: `GatewayError.aiQuotaExceeded` if the rate limit is exceeded.
    func getRecommendation(query: String, context: RecommendationContext) async throws -> AIRecommendation
}
