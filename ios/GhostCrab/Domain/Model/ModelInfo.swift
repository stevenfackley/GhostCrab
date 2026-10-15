import Foundation

/// A language model known to the OpenClaw Gateway.
///
/// - Parameters:
///   - id: Unique identifier used by the gateway (e.g. `"gpt-4o"`).
///   - provider: Provider name (e.g. `"openai"`, `"anthropic"`).
///   - displayName: Human-readable label for UI.
///   - isActive: Whether this model is currently the active/default one.
///   - status: Operational status from the gateway (e.g. `"ready"`, `"auth-error"`).
///   - capabilities: Capability keys (e.g. `"vision"`, `"function-calling"`).
public struct ModelInfo: Codable, Sendable, Identifiable, Hashable {
    public let id: String
    public let provider: String
    public let displayName: String
    public let isActive: Bool
    public let status: String
    public let capabilities: [String]

    public init(
        id: String,
        provider: String,
        displayName: String,
        isActive: Bool,
        status: String,
        capabilities: [String]
    ) {
        self.id = id
        self.provider = provider
        self.displayName = displayName
        self.isActive = isActive
        self.status = status
        self.capabilities = capabilities
    }
}

/// What ``ModelRepository/getModelCatalogue()`` returns: the models plus a configured primary model no single entry
/// matches. Additive (v1.1).
public struct ModelCatalogue: Sendable, Hashable {
    /// As ``ModelRepository/getModels()`` returns them: at most one entry is active.
    public let models: [ModelInfo]
    /// The gateway's primary model (`agents.defaults.model.primary`) exactly as configured, when it names no
    /// catalogue entry or a bare model id that several providers list (then nothing is active); `nil` when it matched
    /// one entry, is unset, or cannot be read.
    public let unlistedPrimary: String?

    public init(models: [ModelInfo], unlistedPrimary: String? = nil) {
        self.models = models
        self.unlistedPrimary = unlistedPrimary
    }
}
