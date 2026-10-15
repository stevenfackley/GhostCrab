import Foundation

/// Represents the current state of the connection to an OpenClaw Gateway.
///
/// Transitions: `.disconnected` → `.connecting` → `.connected` | `.error`
/// From `.connected` or `.error`: → `.disconnected` via explicit disconnect.
/// When the live socket drops: `.connected` → `.connected(reconnecting: true)` → `.connected` (next
/// `sessionGeneration`) | `.error`, without passing through `.connecting`.
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
    ///   - displayName: The gateway host from `hello-ok` (`snapshot.presence`, reason `self`), or "OpenClaw Gateway".
    ///   - version: Gateway version string.
    ///   - authRequirement: The auth mode reported by the gateway.
    ///   - isHttps: Whether the connection uses TLS.
    ///   - capabilities: What this session can do: `"config"`, `"models"` and `"skills"`, each present when the
    ///     gateway offers its methods and the granted scopes allow them (see `GatewayHello.capabilities`).
    ///   - hardwareInfo: Hardware description. Always `nil` since ADR-0007: the gateway reports none.
    ///   - tokenOrNull: The gateway token the session connected with (sent in the WebSocket `connect` request), or
    ///     `nil` if auth is not required.
    ///     Sensitive — never log.
    ///   - reconnecting: Additive (v1.1) — `true` while the session's socket dropped and the connection manager
    ///     makes its one silent reconnect (ADR-0007). The other values are the lost session's; no RPC works meanwhile.
    ///     The state then becomes a new `.connected` (higher `sessionGeneration`) or `.error`. Screens keep what they
    ///     show and neither reload nor report an error for it.
    ///   - sessionGeneration: Additive (v1.1) — increases with every session the connection manager makes live, so a
    ///     session the silent reconnect opened is a new value: screens reload when it changes.
    case connected(
        url: String,
        displayName: String,
        version: String,
        authRequirement: AuthRequirement,
        isHttps: Bool,
        capabilities: [String],
        hardwareInfo: String? = nil,
        tokenOrNull: String? = nil,
        reconnecting: Bool = false,
        sessionGeneration: Int = 0
    )

    /// Connection failed or was lost.
    ///
    /// - Parameters:
    ///   - url: The URL that was being connected to.
    ///   - cause: The error that caused the failure.
    case error(url: String, cause: GatewayError)
}

// MARK: - Session capabilities

/// Additive (v1.1) � the keys of ``GatewayConnection/sessionCapabilities`` (`.connected(capabilities:)`). The Data layer
/// puts a key in when the gateway offers the methods and the granted scopes allow them; screens gate on them.
public enum SessionCapability {
    /// `config.get` + `config.patch` offered and the socket holds `operator.admin`.
    public static let config = "config"
    /// `models.list` offered and the socket holds `operator.read`.
    public static let models = "models"
    /// `skills.status` offered and the socket holds `operator.read`.
    public static let skills = "skills"
}

// MARK: - Session helpers

extension GatewayConnection {

    /// Additive (v1.1) — `true` for the `.connected` state published while the silent reconnect runs.
    public var isReconnecting: Bool {
        if case .connected(_, _, _, _, _, _, _, _, true, _) = self { return true }
        return false
    }

    /// Additive (v1.1) — the live session's generation; `nil` unless `.connected`.
    public var sessionGeneration: Int? {
        if case let .connected(_, _, _, _, _, _, _, _, _, generation) = self { return generation }
        return nil
    }

    /// Additive (v1.1) — what the session can do (`"config"`, `"models"`, `"skills"`); empty unless `.connected`.
    /// A silent reconnect keeps the lost session's capabilities until the new session replaces them.
    public var sessionCapabilities: [String] {
        if case let .connected(_, _, _, _, _, capabilities, _, _, _, _) = self { return capabilities }
        return []
    }
}

// MARK: - Redacted descriptions

/// `description`, `debugDescription` and the reflection mirror never include
/// the gateway token carried by ``GatewayConnection/connected(url:displayName:version:authRequirement:isHttps:capabilities:hardwareInfo:tokenOrNull:reconnecting:sessionGeneration:)``,
/// so a stray `print`, `dump`, string interpolation or log line cannot leak it.
extension GatewayConnection: CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {

    /// Redacted form: `tokenOrNull` shows as `<redacted>` (or `nil`).
    public var description: String {
        switch self {
        case .disconnected:
            return "disconnected"
        case let .connecting(url):
            return "connecting(url: \(url))"
        case let .connected(url, displayName, version, authRequirement, isHttps, capabilities, hardwareInfo, tokenOrNull,
                            reconnecting, sessionGeneration):
            return "connected(url: \(url), displayName: \(displayName), version: \(version), "
                + "authRequirement: \(authRequirement), isHttps: \(isHttps), capabilities: \(capabilities), "
                + "hardwareInfo: \(hardwareInfo ?? "nil"), tokenOrNull: \(Self.redacted(tokenOrNull)), "
                + "reconnecting: \(reconnecting), sessionGeneration: \(sessionGeneration))"
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
        case let .connected(url, displayName, version, authRequirement, isHttps, capabilities, hardwareInfo, tokenOrNull,
                            reconnecting, sessionGeneration):
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
                    "reconnecting": reconnecting,
                    "sessionGeneration": sessionGeneration,
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
