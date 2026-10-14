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

// MARK: - Redacted descriptions

/// `description`, `debugDescription` and the reflection mirror never include
/// the bearer token carried by ``GatewayConnection/connected(url:displayName:version:authRequirement:isHttps:capabilities:hardwareInfo:tokenOrNull:)``,
/// so a stray `print`, `dump`, string interpolation or log line cannot leak it.
extension GatewayConnection: CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {

    /// Redacted form: `tokenOrNull` shows as `<redacted>` (or `nil`).
    public var description: String {
        switch self {
        case .disconnected:
            return "disconnected"
        case let .connecting(url):
            return "connecting(url: \(url))"
        case let .connected(url, displayName, version, authRequirement, isHttps, capabilities, hardwareInfo, tokenOrNull):
            return "connected(url: \(url), displayName: \(displayName), version: \(version), "
                + "authRequirement: \(authRequirement), isHttps: \(isHttps), capabilities: \(capabilities), "
                + "hardwareInfo: \(hardwareInfo ?? "nil"), tokenOrNull: \(Self.redacted(tokenOrNull)))"
        case let .error(url, cause):
            return "error(url: \(url), cause: \(cause))"
        }
    }

    /// Same as ``description``.
    public var debugDescription: String { description }

    /// Mirror without the token, so `dump` stays redacted.
    public var customMirror: Mirror {
        switch self {
        case .disconnected:
            return Mirror(self, children: [:], displayStyle: .enum)
        case let .connecting(url):
            return Mirror(self, children: ["url": url], displayStyle: .enum)
        case let .connected(url, displayName, version, authRequirement, isHttps, capabilities, hardwareInfo, tokenOrNull):
            return Mirror(
                self,
                children: [
                    "url": url,
                    "displayName": displayName,
                    "version": version,
                    "authRequirement": authRequirement,
                    "isHttps": isHttps,
                    "capabilities": capabilities,
                    "hardwareInfo": hardwareInfo ?? "nil",
                    "tokenOrNull": Self.redacted(tokenOrNull),
                ],
                displayStyle: .enum
            )
        case let .error(url, cause):
            return Mirror(self, children: ["url": url, "cause": cause], displayStyle: .enum)
        }
    }

    private static func redacted(_ token: String?) -> String {
        token == nil ? "nil" : "<redacted>"
    }
}
