import Foundation

/// Queries and manages language models registered on the gateway (ADR-0007: `models.list`, and the
/// `agents.defaults.model.primary` config key for the active model).
///
/// **Contract frozen at v1.0.**
public protocol ModelRepository: Sendable {

    /// Fetches the list of all models known to the gateway.
    ///
    /// - Returns: An array of `ModelInfo`, where at most one entry has `isActive == true`
    ///   (none when no primary model is configured or it cannot be read), or an empty list.
    /// - Throws: `GatewayError.api` on gateway errors.
    func getModels() async throws -> [ModelInfo]

    /// Sets the active model on the gateway.
    ///
    /// After this call, a subsequent `getModels()` will reflect the change.
    /// Confirmation dialog is the caller's responsibility.
    ///
    /// - Parameter modelId: The `ModelInfo.id` of the model to activate (`provider/model`).
    /// - Throws: `GatewayError.api` if the model ID is unknown or the swap fails (412 when the gateway config
    ///   changed while the swap was being written: retry; 422 when the gateway rejects the model).
    func setActiveModel(modelId: String) async throws
}
