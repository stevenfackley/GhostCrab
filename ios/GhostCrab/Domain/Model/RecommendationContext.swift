import Foundation

/// Context sent alongside an AI recommendation query.
///
/// Auto-collected from the current session; never includes secrets.
///
/// - Parameters:
///   - activeConfig: The gateway's current config (used for grounding suggestions).
///   - hardwareInfo: Optional hardware description from the gateway's `/status` (e.g. RAM, GPU).
///   - activeModelId: ID of the currently active model, if known.
public struct RecommendationContext: Sendable {
    public let activeConfig: OpenClawConfig
    public let hardwareInfo: String?
    public let activeModelId: String?

    public init(
        activeConfig: OpenClawConfig,
        hardwareInfo: String?,
        activeModelId: String?
    ) {
        self.activeConfig = activeConfig
        self.hardwareInfo = hardwareInfo
        self.activeModelId = activeModelId
    }
}
