import Foundation

/// Queries and manages language models registered on the gateway.
///
/// **Contract frozen at v1.0.**
public protocol ModelRepository: Sendable {

    /// Fetches the list of all models known to the gateway.
    ///
    /// - Returns: An array of `ModelInfo`, where exactly one entry has `isActive == true`
    ///   (or an empty list if no models are configured).
    /// - Throws: `GatewayError.api` on gateway errors.
    func getModels() async throws -> [ModelInfo]

    /// Sets the active model on the gateway.
    ///
    /// After this call, a subsequent `getModels()` will reflect the change.
    /// Confirmation dialog is the caller's responsibility.
    ///
    /// - Parameter modelId: The `ModelInfo.id` of the model to activate.
    /// - Throws: `GatewayError.api` if the model ID is unknown or the swap fails.
    func setActiveModel(modelId: String) async throws
}
