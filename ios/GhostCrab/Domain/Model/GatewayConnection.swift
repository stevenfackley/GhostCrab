import Foundation

/// Represents the current state of the connection to an OpenClaw Gateway.
///
/// Transitions: `.disconnected` → `.connecting` → `.connected` | `.error`
/// From `.connected` or `.error`: → `.disconnected` via explicit disconnect.
public enum GatewayConnection: Sendable {
    /// No active connection. Initial and terminal state.
    case disconnected

    /// Connection handshake in progress.
    ///
    /// - Parameter url: The URL being connected to.
    case connecting(url: String)

    /// Successfully connected and authenticated.
    ///
    /// - Parameters:
    ///   - url: Base URL of the gateway (e.g. `http://192.168.1.50:18789`).
    ///   - displayName: Human-readable name from the gateway's `/status` response.
    ///   - version: Gateway version string.
    ///   - authRequirement: The auth mode reported by the gateway.
    ///   - isHttps: Whether the connection uses TLS.
    ///   - capabilities: Capability keys advertised by the gateway (e.g. `"skill-ai-recommend"`).
    ///   - hardwareInfo: Optional hardware description reported by the gateway's `/status`
    ///     (e.g. RAM, GPU). `nil` if not reported.
    ///   - tokenOrNull: Bearer token for the active session, or `nil` if auth is not required.
    ///     Sensitive — never log.
    case connected(
        url: String,
        displayName: String,
        version: String,
        authRequirement: AuthRequirement,
        isHttps: Bool,
        capabilities: [String],
        hardwareInfo: String? = nil,
        tokenOrNull: String? = nil
    )

    /// Connection failed or was lost.
    ///
    /// - Parameters:
    ///   - url: The URL that was being connected to.
    ///   - cause: The error that caused the failure.
    case error(url: String, cause: GatewayError)
}
