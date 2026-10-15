import Foundation

/// A device token the gateway issued in `hello-ok.auth.deviceToken` (ADR-0007).
public struct StoredDeviceToken: Codable, Sendable, Equatable {
    /// The bearer for `connect.params.auth.deviceToken`. Sensitive — never log.
    public let token: String
    /// The scopes it was granted (reused when reconnecting with it).
    public let scopes: [String]
    /// The role it was issued for (`operator`).
    public let role: String
    /// The ``DeviceIdentity/deviceId`` it belongs to; a token for another identity is ignored.
    public let deviceId: String

    public init(token: String, scopes: [String], role: String, deviceId: String) {
        self.token = token
        self.scopes = scopes
        self.role = role
        self.deviceId = deviceId
    }
}

extension StoredDeviceToken: CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    public var description: String {
        "StoredDeviceToken(token: <redacted>, scopes: \(scopes), role: \(role), deviceId: \(deviceId))"
    }
    public var debugDescription: String { description }
    public var customMirror: Mirror {
        Mirror(self, children: ["token": "<redacted>", "scopes": scopes, "role": role, "deviceId": deviceId], displayStyle: .struct)
    }
}

/// Per-gateway device-token persistence, keyed by ``GatewayKey/of(_:)``.
public protocol DeviceTokenStoring: Sendable {
    /// Never throws for storage trouble: an entry that does not decode reads as `nil` and is removed, so the next
    /// connect falls back to the shared token and the gateway issues a new device token. A Keychain read error also
    /// reads as `nil` but keeps the entry (it may be readable again later).
    ///
    /// - Returns: The stored token, or `nil`.
    func load(gatewayKey: String) -> StoredDeviceToken?
    /// Stores `token`, replacing any previous one.
    ///
    /// - Throws: The Keychain or encoding error; the message never contains the token.
    func save(_ token: StoredDeviceToken, gatewayKey: String) throws
    /// Removes the token. No-op when absent; best effort.
    func clear(gatewayKey: String)
}

/// Device tokens as JSON in the token Keychain service (account `device-token:<gatewayKey>`), so
/// `ProfileStore.clearAll()` (a service-wide `deleteAllTokens()`) wipes them with the profile tokens.
public final class DeviceTokenStore: DeviceTokenStoring, Sendable {

    private let tokens: any TokenStoring

    /// - Parameter tokens: Defaults to the app's token ``Keychain``.
    public init(tokens: any TokenStoring = Keychain()) {
        self.tokens = tokens
    }

    /// Keychain account of the device token for `gatewayKey`.
    static func account(for gatewayKey: String) -> String { "device-token:\(gatewayKey)" }

    public func load(gatewayKey: String) -> StoredDeviceToken? {
        guard let raw = try? tokens.getToken(for: Self.account(for: gatewayKey)) else { return nil }
        guard let decoded = try? JSONDecoder().decode(StoredDeviceToken.self, from: Data(raw.utf8)) else {
            clear(gatewayKey: gatewayKey)
            return nil
        }
        return decoded
    }

    public func save(_ token: StoredDeviceToken, gatewayKey: String) throws {
        let json = String(decoding: try JSONEncoder().encode(token), as: UTF8.self)
        try tokens.setToken(json, for: Self.account(for: gatewayKey))
    }

    public func clear(gatewayKey: String) {
        try? tokens.deleteToken(for: Self.account(for: gatewayKey)) // absent or undeletable: nothing more to do
    }
}

/// Canonical per-gateway key: one device token per `scheme://host:port`.
public enum GatewayKey {
    /// `ws(s)` URLs map to their `http(s)` spelling first, as Android's `gatewayKeyOf` does via
    /// `GatewayUrl.fromWebSocketUrl`: `ws://gw:18789/ws` and `http://gw:18789` (or `wss://gw/ws` and `https://gw`)
    /// share one key, so keys saved before `ws(s)` input was accepted stay valid.
    ///
    /// - Returns: `scheme://host:port` (``PairingLink/gatewayURL(from:)``, so a path, query or fragment is ignored and
    ///   the default port is filled in) with the host lowercased, as Android's `gatewayKeyOf` does; or the trimmed
    ///   input without trailing `/` when it does not parse.
    public static func of(_ url: String) -> String {
        if let normalised = PairingLink.gatewayURL(from: url) ?? PairingLink.webSocketGatewayURL(from: url) {
            // Only scheme, host and port remain; scheme is already lowercase and host names are case-insensitive.
            return normalised.absoluteString.lowercased()
        }
        var trimmed = url.trimmingCharacters(in: .whitespacesAndNewlines)
        while trimmed.hasSuffix("/") { trimmed.removeLast() }
        return trimmed
    }
}
