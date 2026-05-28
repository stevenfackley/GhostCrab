import Foundation

/// Frozen v1.1 contract — additive only. Install + list + uninstall via gateway WebSocket.
///
/// All methods assume an active `GatewayConnection.connected` state. Callers get an
/// `AsyncStream` so the UI can stream progress notifications live.
public protocol InstalledSkillRepository: Sendable {

    /// Hot stream of the current installed-skill list. Refreshed when the gateway reports
    /// install/uninstall success. Starts empty until the first `refreshFromGateway()` succeeds.
    func observeInstalled() -> AsyncStream<[InstalledSkill]>

    /// One-shot refresh from gateway `skills.list`.
    ///
    /// - Returns: A `Result` containing the fetched list on success, or an `Error` on failure.
    func refreshFromGateway() async -> Result<[InstalledSkill], any Error>

    /// Start an install. The returned stream emits a terminal
    /// `SkillInstallProgress.succeeded` or `SkillInstallProgress.failed` and then completes.
    ///
    /// - Parameters:
    ///   - slug: e.g. `"wanng-ide/auto-skill-hunter"`.
    ///   - version: `nil` → latest.
    /// - Returns: An `AsyncStream` of install progress events.
    func install(slug: String, version: String?) -> AsyncStream<SkillInstallProgress>

    /// Remove a previously-installed skill.
    ///
    /// - Parameter slug: The slug of the skill to uninstall.
    /// - Returns: A `Result` indicating success or failure.
    func uninstall(slug: String) async -> Result<Void, any Error>
}
