import Foundation

/// Reads and writes the OpenClaw Gateway's `openclaw.json` configuration.
///
/// Optimistic UI is **forbidden** — the gateway is the source of truth.
/// After every `updateConfig`, callers must call `getConfig()` to reconcile.
///
/// **Contract frozen at v1.0.**
public protocol ConfigRepository: Sendable {

    /// Fetches the full configuration from the gateway.
    ///
    /// - Returns: The current `OpenClawConfig` with all sections and optional ETag.
    /// - Throws: `GatewayError.api` if the gateway returns an unexpected HTTP error.
    func getConfig() async throws -> OpenClawConfig

    /// Applies a JSON merge-patch update to a single top-level config section.
    ///
    /// Does **not** perform an optimistic local update. Callers must call `getConfig()`
    /// afterward to read the authoritative post-write state.
    ///
    /// If the gateway supports ETags and the config has been modified by another client
    /// since `getConfig()` was called, the gateway returns 412; the implementation maps
    /// this to a `GatewayError.api` with `statusCode = 412`.
    ///
    /// - Parameters:
    ///   - section: Top-level section key to update (e.g. `"gateway"`).
    ///   - value: JSON merge-patch value for the section.
    /// - Throws: `GatewayError.configValidation` if the value fails server-side validation.
    /// - Throws: `GatewayError.api` on unexpected gateway errors.
    func updateConfig(section: String, value: AnyCodable) async throws
}
