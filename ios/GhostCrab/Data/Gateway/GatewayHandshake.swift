import Foundation

/// `connect.params.client` (ADR-0007). `id` and `mode` must be values upstream's allow-lists accept (checked before
/// auth: anything else is `INVALID_REQUEST invalid connect params`, close 1008); `version` and `displayName` stay
/// honest. `platform` is bound into the device signature and pinned when the device is approved: changing it
/// re-triggers pairing (`metadata-upgrade`), so it is a constant, never derived from the device. No `deviceFamily`.
public struct GatewayClientInfo: Sendable, Equatable {

    /// Upstream allow-listed id for the iOS app; any other id is rejected before auth (verified 2026-10-15).
    public static let iosClientID = "openclaw-ios"

    /// Pinned at approval (case-insensitive). The same on iPhone, iPad and Mac Catalyst; never derive it.
    public static let iosPlatform = "ios"

    /// Upstream client id.
    public let id: String
    /// GhostCrab's marketing version (honest; not checked by the gateway).
    public let version: String
    /// `ios`.
    public let platform: String
    /// Shown in the gateway's pending-pairing list.
    public let displayName: String
    /// Upstream client mode; `ui` for an interactive operator app.
    public let mode: String
    /// Optional; bound into the signature and pinned at approval, so it must never change. GhostCrab sends none.
    public let deviceFamily: String?
    /// BCP-47 tag for `connect.params.locale`.
    public let locale: String

    public init(id: String, version: String, platform: String, displayName: String, mode: String = "ui",
                deviceFamily: String? = nil, locale: String) {
        self.id = id
        self.version = version
        self.platform = platform
        self.displayName = displayName
        self.mode = mode
        self.deviceFamily = deviceFamily
        self.locale = locale
    }

    /// `connect.params.userAgent`.
    public var userAgent: String { "ghostcrab-\(platform)/\(version)" }

    /// This build's client info: the constant id, mode and platform with this build's version.
    public static func current(bundle: Bundle = .main) -> GatewayClientInfo {
        GatewayClientInfo(
            id: iosClientID,
            version: bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.0.0",
            platform: iosPlatform,
            displayName: "GhostCrab (iOS)",
            locale: Locale.current.identifier(.bcp47)
        )
    }
}

/// Credential for `connect.params.auth`. `description`, `debugDescription` and the mirror redact every secret.
public enum GatewayConnectAuth: Sendable {
    /// The gateway's shared token (`auth.token`). Sensitive.
    case sharedToken(String)
    /// A device token this gateway issued earlier (`auth.deviceToken`), reused with its granted scopes. Sensitive.
    case deviceToken(String, scopes: [String])
    /// No credential: `auth.mode: none` gateways and the auth probe.
    case none

    /// Upstream `selectGatewayConnectAuth` order for an operator client: shared token, stored device token, nothing.
    /// Setup-code bootstrap tokens are not a credential here: the gateway accepts them only for `role: "node"`
    /// (verified 2026-10-15, ADR-0007).
    ///
    /// - Parameters:
    ///   - sharedToken: Token the user or profile supplied.
    ///   - stored: This gateway's stored device token, already filtered to the current identity.
    /// - Returns: The credential to send.
    public static func select(sharedToken: String?, stored: StoredDeviceToken?) -> GatewayConnectAuth {
        if let sharedToken { return .sharedToken(sharedToken) }
        if let stored { return .deviceToken(stored.token, scopes: stored.scopes) }
        return .none
    }

    /// The value bound into the signature's token field. Sensitive.
    var signatureToken: String? {
        switch self {
        case .sharedToken(let token), .deviceToken(let token, _): return token
        case .none: return nil
        }
    }

    /// Scopes to request: a device token's own (when it has some), else the operator set.
    var scopes: [String] {
        if case let .deviceToken(_, scopes) = self, !scopes.isEmpty { return scopes }
        return GatewayHandshake.operatorScopes
    }
}

extension GatewayConnectAuth: CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    public var description: String {
        switch self {
        case .sharedToken: return "sharedToken(<redacted>)"
        case let .deviceToken(_, scopes): return "deviceToken(<redacted>, scopes: \(scopes))"
        case .none: return "none"
        }
    }
    public var debugDescription: String { description }
    /// The default enum mirror would expose the associated token to `dump` and the debugger.
    public var customMirror: Mirror { Mirror(self, children: ["credential": description], displayStyle: .enum) }
}

/// `connect.challenge` payload.
public struct ConnectChallenge: Sendable, Equatable {
    /// Signed and echoed in `device.nonce`.
    public let nonce: String
    /// Server time in ms; used as `device.signedAt`.
    public let ts: Int64

    public init(nonce: String, ts: Int64) {
        self.nonce = nonce
        self.ts = ts
    }

    /// - Throws: ``GatewayError/protocolViolation(url:detail:)`` unless there is a non-blank `nonce` and a
    ///   non-negative integer `ts` (upstream treats anything else as invalid).
    public static func parse(endpoint: String, payload: AnyCodable?) throws -> ConnectChallenge {
        let object = payload?.objectValue
        guard let nonce = object?["nonce"]?.value as? String,
              !nonce.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let ts = object?["ts"]?.value as? Int, ts >= 0 else {
            throw GatewayError.protocolViolation(url: endpoint, detail: "connect.challenge without a nonce and a non-negative integer ts")
        }
        return ConnectChallenge(nonce: nonce, ts: Int64(ts))
    }
}

/// The parts of `hello-ok` GhostCrab uses. `description`, `debugDescription` and the mirror redact the device token.
public struct GatewayHello: Sendable, Equatable {

    /// Scope for read methods (`status`, `models.list`, `skills.status`; ADR-0007).
    public static let scopeOperatorRead = "operator.read"
    /// Scope for `config.*` and `skills.install` (ADR-0007).
    public static let scopeOperatorAdmin = "operator.admin"

    /// `server.version`, e.g. `2026.10.3` (`unknown` when absent).
    public let serverVersion: String
    /// `features.methods`.
    public let methods: Set<String>
    /// `auth.method`: `token`, `device-token`, `bootstrap-token`, `none`, ….
    public let authMethod: String?
    /// `auth.role`.
    public let role: String
    /// `auth.scopes`, in server order: the socket's live authority.
    public let scopes: [String]
    /// `auth.deviceToken`, when issued. Sensitive.
    public let deviceToken: String?
    /// `snapshot.authMode`; absent on a scope-less socket.
    public let gatewayAuthMode: String?
    /// `host` of the `snapshot.presence` entry with `reason: "self"`.
    public let gatewayHost: String?

    /// ADR-0007 replacement for ADR-002's probe: the gateway's auth mode from `hello-ok`.
    ///
    /// - Parameter token: The shared token used for this connect (decides when the hello says nothing).
    /// - Returns: `.none` for `none`, else `.token`.
    public func authRequirement(token: String?) -> AuthRequirement {
        switch gatewayAuthMode ?? authMethod {
        case "none"?: return AuthRequirement.none
        case nil: return token == nil ? AuthRequirement.none : AuthRequirement.token
        default: return AuthRequirement.token
        }
    }

    /// What this socket can actually do (Android `gatewayCapabilities`): a method the gateway offers counts only
    /// when the granted scopes allow it, so a token-only socket (`scopes: []`) advertises nothing and the UI never
    /// offers a screen whose calls would fail with `missing scope`. `config` needs `operator.admin`; `models` and
    /// `skills` need `operator.read` (taken literally; `operator.admin` alone is not assumed to cover read).
    ///
    /// - Returns: GhostCrab capability keys, in a stable order.
    public var capabilities: [String] {
        let admin = scopes.contains(Self.scopeOperatorAdmin)
        let read = scopes.contains(Self.scopeOperatorRead)
        var keys: [String] = []
        if admin, methods.contains("config.get"), methods.contains("config.patch") { keys.append(SessionCapability.config) }
        if read, methods.contains("models.list") { keys.append(SessionCapability.models) }
        if read, methods.contains("skills.status") { keys.append(SessionCapability.skills) }
        return keys
    }
}

extension GatewayHello: CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    public var description: String {
        "GatewayHello(serverVersion: \(serverVersion), methods: \(methods.count), authMethod: \(authMethod ?? "nil"), "
            + "role: \(role), scopes: \(scopes), deviceToken: \(deviceToken == nil ? "nil" : "<redacted>"), "
            + "gatewayAuthMode: \(gatewayAuthMode ?? "nil"), gatewayHost: \(gatewayHost ?? "nil"))"
    }
    public var debugDescription: String { description }
    public var customMirror: Mirror {
        Mirror(self, children: [
            "serverVersion": serverVersion, "methods": methods.count, "authMethod": authMethod ?? "nil", "role": role,
            "scopes": scopes, "deviceToken": deviceToken == nil ? "nil" : "<redacted>",
            "gatewayAuthMode": gatewayAuthMode ?? "nil", "gatewayHost": gatewayHost ?? "nil",
        ], displayStyle: .struct)
    }
}

/// `connect.params`. Optional members are omitted from the JSON when `nil` (synthesised `encodeIfPresent`).
struct ConnectParams: Encodable, Sendable {
    struct Client: Encodable, Sendable {
        let id: String
        let version: String
        let platform: String
        let mode: String
        let displayName: String
        let deviceFamily: String?
    }
    struct Auth: Encodable, Sendable {
        let token: String?
        let deviceToken: String?
    }
    struct Device: Encodable, Sendable {
        let id: String
        let publicKey: String
        let signature: String
        let signedAt: Int64
        let nonce: String
    }
    let minProtocol: Int
    let maxProtocol: Int
    let client: Client
    let role: String
    let scopes: [String]
    let caps: [String]
    let commands: [String]
    let permissions: [String: Bool]
    let auth: Auth?
    let locale: String
    let userAgent: String
    let device: Device
}

// `connect.params` carries the credential: descriptions and mirrors never show it.
extension ConnectParams: CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    var description: String {
        "ConnectParams(client: \(client.id)/\(client.platform)/\(client.mode), role: \(role), scopes: \(scopes), "
            + "auth: \(auth?.description ?? "nil"), device: \(device.id))"
    }
    var debugDescription: String { description }
    var customMirror: Mirror {
        Mirror(self, children: [
            "client": client, "role": role, "scopes": scopes, "auth": auth?.description ?? "nil", "locale": locale,
            "userAgent": userAgent, "device": device.id,
        ], displayStyle: .struct)
    }
}

extension ConnectParams.Auth: CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    var description: String {
        "Auth(token: \(token == nil ? "nil" : "<redacted>"), deviceToken: \(deviceToken == nil ? "nil" : "<redacted>"))"
    }
    var debugDescription: String { description }
    var customMirror: Mirror { Mirror(self, children: ["auth": description], displayStyle: .struct) }
}

/// The `connect` exchange (ADR-0007 decision 3). The connect frame is never logged: it carries the credential.
public enum GatewayHandshake {

    /// GhostCrab connects as an operator client.
    public static let roleOperator = "operator"

    /// Requested unless a stored device token says otherwise (`config.*` and `skills.install` need admin).
    public static let operatorScopes = ["operator.read", "operator.write", "operator.admin"]

    /// Time for the gateway to answer `connect`.
    public static let connectTimeout: Duration = .seconds(15)

    /// Time for the gateway's `connect.challenge` (the real one sends it within ~2 ms of the upgrade).
    public static let challengeTimeout: Duration = .seconds(10)

    /// Waits for the challenge, sends the signed `connect`, parses `hello-ok`. A caller's own cancellation
    /// propagates as `CancellationError` (the RPC client never turns it into a gateway error).
    ///
    /// - Parameters:
    ///   - rpc: A freshly started socket (nothing sent yet).
    ///   - client: This app's client info.
    ///   - auth: The credential from ``GatewayConnectAuth/select(sharedToken:stored:)``.
    ///   - identity: This install's device identity.
    ///   - challengeTimeout: How long to wait for `connect.challenge`.
    /// - Returns: The parsed hello.
    /// - Throws: `.pairingRequired`, `.connectRejected`, `.rpc` (e.g. a client id outside the allow-list:
    ///   `INVALID_REQUEST invalid connect params`), `.protocolViolation` (malformed challenge or hello), `.timeout`,
    ///   `.unreachable` or `.connectionClosed` (transport).
    public static func perform(rpc: GatewayRPCClient, client: GatewayClientInfo, auth: GatewayConnectAuth,
                               identity: DeviceIdentity,
                               challengeTimeout: Duration = GatewayHandshake.challengeTimeout) async throws -> GatewayHello {
        let event = try await rpc.awaitChallenge(timeout: challengeTimeout)
        let challenge = try ConnectChallenge.parse(endpoint: rpc.endpoint, payload: event.payload)
        let params = try connectParams(client: client, auth: auth, identity: identity, challenge: challenge)
        let payload = try await rpc.request("connect", params: params, timeout: connectTimeout)
        return try parseHello(endpoint: rpc.endpoint, payload: payload)
    }

    /// - Returns: `connect.params` with a v3 device signature over `challenge`. Carries the credential: never log it.
    /// - Throws: CryptoKit's signing error (never for a valid identity).
    static func connectParams(client: GatewayClientInfo, auth: GatewayConnectAuth, identity: DeviceIdentity,
                              challenge: ConnectChallenge) throws -> ConnectParams {
        let scopes = auth.scopes
        let signed = DeviceAuthPayload(
            deviceId: identity.deviceId, clientId: client.id, clientMode: client.mode, role: roleOperator,
            scopes: scopes, signedAtMs: challenge.ts, token: auth.signatureToken, nonce: challenge.nonce,
            platform: client.platform, deviceFamily: client.deviceFamily
        ).v3
        let authBlock: ConnectParams.Auth?
        switch auth {
        case .sharedToken(let token): authBlock = ConnectParams.Auth(token: token, deviceToken: nil)
        case .deviceToken(let token, _): authBlock = ConnectParams.Auth(token: nil, deviceToken: token)
        case .none: authBlock = nil
        }
        return ConnectParams(
            minProtocol: GatewayProtocol.version,
            maxProtocol: GatewayProtocol.version,
            client: .init(id: client.id, version: client.version, platform: client.platform, mode: client.mode,
                          displayName: client.displayName, deviceFamily: client.deviceFamily),
            role: roleOperator,
            scopes: scopes,
            caps: [],
            commands: [],
            permissions: [:],
            auth: authBlock,
            locale: client.locale,
            userAgent: client.userAgent,
            device: .init(id: identity.deviceId, publicKey: identity.publicKeyBase64URL,
                          signature: try identity.sign(signed), signedAt: challenge.ts, nonce: challenge.nonce)
        )
    }

    /// Parsed by hand and leniently, like Android's `parseHelloOk`: only `type`, `auth`, `server` and `protocol` are
    /// required; a malformed optional field reads as absent instead of failing the connect.
    ///
    /// - Throws: ``GatewayError/protocolViolation(url:detail:)`` unless `payload` is a protocol-4 `hello-ok`.
    public static func parseHello(endpoint: String, payload: AnyCodable?) throws -> GatewayHello {
        guard let object = payload?.objectValue, let auth = object["auth"]?.objectValue,
              object["type"]?.value as? String == "hello-ok" else {
            throw GatewayError.protocolViolation(url: endpoint, detail: "connect answered without a hello-ok payload")
        }
        let negotiated = object["protocol"]?.value as? Int
        guard let server = object["server"]?.objectValue, negotiated == GatewayProtocol.version else {
            let shown = negotiated.map(String.init) ?? "none"
            throw GatewayError.protocolViolation(
                url: endpoint,
                detail: "gateway negotiated protocol \(shown); GhostCrab speaks \(GatewayProtocol.version)"
            )
        }
        let snapshot = object["snapshot"]?.objectValue
        let presence = (snapshot?["presence"]?.value as? [AnyCodable]) ?? []
        let selfEntry = presence.lazy.compactMap { $0.objectValue }.first { $0["reason"]?.value as? String == "self" }
        return GatewayHello(
            serverVersion: server["version"]?.value as? String ?? "unknown",
            methods: Set(strings(object["features"]?.objectValue?["methods"])),
            authMethod: auth["method"]?.value as? String,
            role: auth["role"]?.value as? String ?? roleOperator,
            scopes: strings(auth["scopes"]),
            deviceToken: auth["deviceToken"]?.value as? String,
            gatewayAuthMode: snapshot?["authMode"]?.value as? String,
            gatewayHost: selfEntry?["host"]?.value as? String
        )
    }

    /// - Returns: The string elements of a JSON array (others skipped); empty when absent or not an array.
    private static func strings(_ value: AnyCodable?) -> [String] {
        (value?.value as? [AnyCodable])?.compactMap { $0.value as? String } ?? []
    }
}
