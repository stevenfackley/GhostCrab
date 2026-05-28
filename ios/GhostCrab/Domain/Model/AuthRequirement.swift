import Foundation

/// Authentication mode required by a gateway.
///
/// Determined by probing `/health` then `/status` without credentials.
/// Password-based auth is mapped to `.token` for v1.0; documented in ADR-002.
public enum AuthRequirement: String, Codable, Sendable, CaseIterable {
    /// Gateway accepts requests without any authentication. Security risk — surface banner.
    case none

    /// Gateway requires a Bearer token in the `Authorization` header.
    case token
}
