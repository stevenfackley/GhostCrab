import Foundation

/// URLSession-backed ``ModelRepository`` that talks to the connected gateway.
///
/// Requires an active connection — ``GatewayConnectionManagerImpl/requireClient()``
/// throws if called while disconnected, which propagates to callers as
/// ``GatewayError/unreachable(url:underlying:)``.
///
/// Direct port of `ModelRepositoryImpl.kt`. The Kotlin file performs a
/// `ModelDto.toModelInfo()` mapping — the Swift DTO and domain model share the
/// same shape so the mapping is a direct field-by-field copy.
public final class ModelRepositoryImpl: ModelRepository, Sendable {

    private let connectionManager: GatewayConnectionManagerImpl

    /// - Parameter connectionManager: Concrete manager used to obtain the
    ///   active ``OpenClawAPIClient``.
    public init(connectionManager: GatewayConnectionManagerImpl) {
        self.connectionManager = connectionManager
    }

    /// Fetches the current model list from `GET /api/models/status`.
    ///
    /// - Returns: List of ``ModelInfo`` mapped 1:1 from the gateway DTOs.
    /// - Throws: ``GatewayError`` on network/API errors.
    /// - Throws: ``GatewayError/unreachable(url:underlying:)`` if no gateway
    ///   connection is active.
    public func getModels() async throws -> [ModelInfo] {
        let client = try await connectionManager.requireClient()
        let dtos = try await client.getModels()
        return dtos.map { $0.toModelInfo() }
    }

    /// Sets the active model via `POST /api/models/active`.
    ///
    /// - Parameter modelId: The `id` of the model to activate.
    /// - Throws: ``GatewayError`` on network/API errors.
    /// - Throws: ``GatewayError/unreachable(url:underlying:)`` if no gateway
    ///   connection is active.
    public func setActiveModel(modelId: String) async throws {
        let client = try await connectionManager.requireClient()
        try await client.setActiveModel(modelId)
    }
}

// ── Mapping ──────────────────────────────────────────────────────────────────

private extension ModelDTO {
    func toModelInfo() -> ModelInfo {
        ModelInfo(
            id: id,
            provider: provider,
            displayName: displayName,
            isActive: isActive,
            status: status,
            capabilities: capabilities
        )
    }
}
