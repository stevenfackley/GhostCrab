import Foundation

/// ``AIRecommendationService`` for gateways without a recommendation API: every OpenClaw gateway as of 2026.10.3
/// (`POST /api/ai/recommend` is 404 and no WS method exists; QA §8.11, ADR-0007). Never available; a request fails
/// with ``GatewayError/aiServiceUnavailable(url:)`` without any network call, so no config (secrets included) ever
/// leaves the device. A `chat.send`-based implementation needs its own ADR (it changes ADR-004's contract). The
/// Dashboard hides the AI tile while ``isAvailable()`` is `false`.
public final class AIRecommendationServiceImpl: AIRecommendationService, Sendable {

    private let connectionManager: any GatewayConnectionManager

    /// - Parameter connectionManager: Supplies the connected gateway's URL for the error.
    public init(connectionManager: any GatewayConnectionManager) {
        self.connectionManager = connectionManager
    }

    /// - Returns: Always `false`: no OpenClaw gateway offers recommendations (ADR-0007).
    public func isAvailable() async -> Bool {
        false
    }

    /// - Parameters:
    ///   - query: Ignored.
    ///   - context: Ignored; never sent anywhere.
    /// - Returns: Never returns.
    /// - Throws: ``GatewayError/aiServiceUnavailable(url:)`` naming the connected gateway (not retryable);
    ///   ``GatewayError/unreachable(url:underlying:)`` while disconnected.
    public func getRecommendation(query: String, context: RecommendationContext) async throws -> AIRecommendation {
        guard case let .connected(url, _, _, _, _, _, _, _, _, _) = connectionManager.currentState else {
            throw GatewayError.unreachable(url: "<no active connection>", underlying: nil)
        }
        throw GatewayError.aiServiceUnavailable(url: url)
    }
}
