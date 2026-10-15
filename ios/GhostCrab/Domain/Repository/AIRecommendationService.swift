import Foundation

/// Submits AI recommendation queries to the gateway.
///
/// Check `isAvailable()` first. OpenClaw gateways have no recommendation API, and no skill adds one (ADR-0007,
/// amends ADR-004): the current implementation is never available and the UI explains that, until a
/// `chat.send`-based design gets its own ADR.
///
/// **Contract frozen at v1.0.**
public protocol AIRecommendationService: Sendable {

    /// Returns whether the connected gateway offers AI recommendations.
    ///
    /// Does not make an AI call.
    ///
    /// - Returns: `true` if recommendations can be requested.
    func isAvailable() async -> Bool

    /// Submits a recommendation query to the gateway.
    ///
    /// The `context` is auto-collected and attached to the request body. It never contains
    /// secrets (tokens are stripped before serialization).
    ///
    /// - Parameters:
    ///   - query: Free-text user query (e.g. `"best coding model for 16GB RAM"`).
    ///   - context: Current session context for grounding the recommendation.
    /// - Returns: The `AIRecommendation` containing the response and optional suggested changes.
    /// - Throws: `GatewayError.aiServiceUnavailable` if the gateway offers no recommendations.
    /// - Throws: `GatewayError.aiQuotaExceeded` if the rate limit is exceeded.
    func getRecommendation(query: String, context: RecommendationContext) async throws -> AIRecommendation
}
