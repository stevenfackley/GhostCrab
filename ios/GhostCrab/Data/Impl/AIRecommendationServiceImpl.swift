import Foundation

/// URLSession-backed ``AIRecommendationService`` that proxies queries to the
/// gateway's AI skill.
///
/// ``isAvailable()`` is a capability check against the cached connection state
/// — no network call. ``getRecommendation(query:context:)`` POSTs to
/// `POST /api/ai/recommend` on the connected gateway.
///
/// Direct port of `AIRecommendationServiceImpl.kt`.
///
/// **Note on error mapping**: the Kotlin source catches `GatewayApiException`
/// with status 404/429 and re-throws domain-specific subclasses. The Swift
/// ``OpenClawAPIClient`` already throws ``GatewayError/aiServiceUnavailable``
/// and ``GatewayError/aiQuotaExceeded`` directly for those status codes — so
/// here we just let them propagate. The visible-from-callers behaviour is
/// identical.
public final class AIRecommendationServiceImpl: AIRecommendationService, Sendable {

    private static let skillKey = "ai.recommend"

    private let connectionManager: GatewayConnectionManagerImpl

    /// - Parameter connectionManager: Provides the active ``OpenClawAPIClient``
    ///   and connection state.
    public init(connectionManager: GatewayConnectionManagerImpl) {
        self.connectionManager = connectionManager
    }

    /// Returns `true` if the currently-connected gateway advertises the
    /// `ai.recommend` capability in its `/status` response.
    ///
    /// Reads the in-memory connection state — no network call is made.
    public func isAvailable() async -> Bool {
        let state = await connectionManager.currentState
        if case let .connected(_, _, _, _, _, capabilities, _, _) = state {
            return capabilities.contains(Self.skillKey)
        }
        return false
    }

    /// Submits `query` and auto-collected `context` to `POST /api/ai/recommend`.
    ///
    /// - Parameters:
    ///   - query: Free-text user query.
    ///   - context: Session context assembled by the ViewModel.
    /// - Returns: ``AIRecommendation`` mapped from the gateway DTO.
    /// - Throws: ``GatewayError/aiServiceUnavailable(url:)`` on 404
    ///   (skill not installed).
    /// - Throws: ``GatewayError/aiQuotaExceeded(url:)`` on 429 (rate limit).
    /// - Throws: ``GatewayError`` on other network errors.
    /// - Throws: ``GatewayError/unreachable(url:underlying:)`` if no active
    ///   connection exists.
    public func getRecommendation(
        query: String,
        context: RecommendationContext
    ) async throws -> AIRecommendation {
        let client = try await connectionManager.requireClient()
        let request = AIRecommendationRequestDTO(
            query: query,
            context: AIContextDTO(
                activeConfig: context.activeConfig.sections,
                hardwareInfo: context.hardwareInfo,
                activeModelId: context.activeModelId
            )
        )
        let dto = try await client.getAIRecommendation(request)
        return AIRecommendation(
            query: query,
            recommendation: dto.recommendation,
            suggestedChanges: dto.suggestedChanges.map { change in
                SuggestedChange(
                    section: change.section,
                    key: change.key,
                    currentValue: change.currentValue,
                    suggestedValue: change.suggestedValue,
                    rationale: change.rationale
                )
            }
        )
    }
}
