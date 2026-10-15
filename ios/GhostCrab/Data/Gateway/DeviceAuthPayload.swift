import Foundation

/// Fields bound into the device signature of `connect` (upstream `buildDeviceAuthPayloadV3`, ADR-0007). Byte for
/// byte the layout of Android's `DeviceAuthPayload.v3()`.
public struct DeviceAuthPayload: Sendable {
    /// ``DeviceIdentity/deviceId``.
    public let deviceId: String
    /// `connect.params.client.id`.
    public let clientId: String
    /// `connect.params.client.mode`.
    public let clientMode: String
    /// `connect.params.role`.
    public let role: String
    /// `connect.params.scopes`, in request order.
    public let scopes: [String]
    /// The challenge `ts` (also sent as `device.signedAt`).
    public let signedAtMs: Int64
    /// The credential sent in `auth` (shared or device token), or `nil`. Sensitive.
    public let token: String?
    /// The challenge nonce (also sent as `device.nonce`).
    public let nonce: String
    /// `connect.params.client.platform`.
    public let platform: String
    /// `connect.params.client.deviceFamily`, or `nil`.
    public let deviceFamily: String?

    public init(deviceId: String, clientId: String, clientMode: String, role: String, scopes: [String],
                signedAtMs: Int64, token: String?, nonce: String, platform: String, deviceFamily: String?) {
        self.deviceId = deviceId
        self.clientId = clientId
        self.clientMode = clientMode
        self.role = role
        self.scopes = scopes
        self.signedAtMs = signedAtMs
        self.token = token
        self.nonce = nonce
        self.platform = platform
        self.deviceFamily = deviceFamily
    }

    /// `v3|deviceId|clientId|mode|role|scopes|signedAt|token|nonce|platform|deviceFamily`. Contains the cleartext
    /// ``token``: sign it and drop it, never log or persist it.
    public var v3: String {
        [
            "v3", deviceId, clientId, clientMode, role, scopes.joined(separator: ","), String(signedAtMs),
            token ?? "", nonce, Self.normalize(platform), Self.normalize(deviceFamily),
        ].joined(separator: "|")
    }

    /// Upstream `normalizeDeviceMetadataForAuth`: trim, then lowercase ASCII `A`–`Z` only.
    static func normalize(_ value: String?) -> String {
        guard let value else { return "" }
        let lowered = value.trimmingCharacters(in: .whitespacesAndNewlines).unicodeScalars.map { scalar in
            (65...90).contains(scalar.value) ? Character(Unicode.Scalar(scalar.value + 32) ?? scalar) : Character(scalar)
        }
        return String(lowered)
    }
}

extension DeviceAuthPayload: CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    public var description: String {
        "DeviceAuthPayload(deviceId: \(deviceId), clientId: \(clientId), role: \(role), scopes: \(scopes), "
            + "signedAtMs: \(signedAtMs), token: \(token == nil ? "nil" : "<redacted>"), nonce: \(nonce), "
            + "platform: \(platform))"
    }
    public var debugDescription: String { description }
    /// `dump` and debugger views see the redacted token only.
    public var customMirror: Mirror {
        Mirror(self, children: [
            "deviceId": deviceId, "clientId": clientId, "clientMode": clientMode, "role": role, "scopes": scopes,
            "signedAtMs": signedAtMs, "token": token == nil ? "nil" : "<redacted>", "nonce": nonce,
            "platform": platform, "deviceFamily": deviceFamily ?? "nil",
        ], displayStyle: .struct)
    }
}
