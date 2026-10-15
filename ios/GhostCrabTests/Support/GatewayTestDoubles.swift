import Foundation
import Testing
@testable import GhostCrab

/// Always returns the RFC 8032 TEST 1 identity; `regenerated` is what the test says.
struct FixedIdentityProvider: DeviceIdentityProviding {
    var wasRegenerated = false

    func identity() async throws -> DeviceIdentity { try DeviceIdentity(seed: TestKeys.rfcSeed) }

    var regenerated: Bool {
        get async { wasRegenerated }
    }
}

/// Map-backed ``DeviceTokenStoring``. `failSaves` makes every `save` throw, like a Keychain that refuses writes.
final class InMemoryDeviceTokenStore: DeviceTokenStoring, @unchecked Sendable {

    struct SaveRefused: Error {}

    private let lock = NSLock()
    private var tokens: [String: StoredDeviceToken]
    private let failSaves: Bool

    init(_ tokens: [String: StoredDeviceToken] = [:], failSaves: Bool = false) {
        self.tokens = tokens
        self.failSaves = failSaves
    }

    func load(gatewayKey: String) -> StoredDeviceToken? { lock.withLock { tokens[gatewayKey] } }

    func save(_ token: StoredDeviceToken, gatewayKey: String) throws {
        if failSaves { throw SaveRefused() }
        lock.withLock { tokens[gatewayKey] = token }
    }

    func clear(gatewayKey: String) { lock.withLock { tokens[gatewayKey] = nil } }
}

/// REST clients routed through ``MockURLProtocol`` (register a handler for the base URL to make `/health` answer).
struct MockRESTFactory: OpenClawAPIClientFactory {
    func unauthenticated(baseURL: URL, allowCleartextPublicIPs: Bool) -> OpenClawAPIClient {
        OpenClawAPIClient.makeForTesting(
            baseURL: baseURL,
            allowCleartextPublicIPs: allowCleartextPublicIPs,
            protocolClasses: [MockURLProtocol.self]
        )
    }
}

/// `params` of the first `method` request `socket` received.
func sentParams(_ socket: ScriptedGatewayWebSocket, _ method: String) async throws -> [String: Any] {
    let text = try #require(await socket.sentRequest(method))
    return try #require(try jsonObject(text)["params"] as? [String: Any])
}

/// Waits (up to ~3 s) until the client has put a `method` request on `socket`.
func waitUntilSent(_ socket: ScriptedGatewayWebSocket, _ method: String) async throws {
    for _ in 0..<300 {
        if await socket.sentMethods().contains(method) { return }
        try await Task.sleep(for: .milliseconds(10))
    }
    Issue.record("the client never sent \(method)")
}

/// `{"type":"res","ok":true,"payload":{"ok":true}}`: a minimal success for writes whose answer a test does not read.
/// Tests that read the answer use the captured frames (`res-config-patch`, `res-skills-update`, `res-skills-install`).
let okReply = #"{"type":"res","id":"x","ok":true,"payload":{"ok":true}}"#

/// An `ok:false` frame with `code`, `message` and optional `details.code`.
func rejectionFrame(code: String = "INVALID_REQUEST", message: String, detailCode: String? = nil) throws -> String {
    var error: [String: Any] = ["code": code, "message": message]
    if let detailCode { error["details"] = ["code": detailCode] }
    let frame: [String: Any] = ["type": "res", "id": "x", "ok": false, "error": error]
    return String(decoding: try JSONSerialization.data(withJSONObject: frame), as: UTF8.self)
}

/// A started RPC client over `socket` (no challenge needed for repository tests).
func startedRPC(_ socket: ScriptedGatewayWebSocket, endpoint: String = "ws://gw/ws") async -> GatewayRPCClient {
    let rpc = GatewayRPCClient(socket: socket, endpoint: endpoint)
    await rpc.start()
    return rpc
}
