import CryptoKit
import Foundation
import Testing
@testable import GhostCrab

@Suite("Gateway handshake")
struct GatewayHandshakeTests {

    private let client = GatewayClientInfo(id: "openclaw-ios", version: "0.3.0", platform: "ios", displayName: "GhostCrab (iOS)", locale: "en-US")

    private func rpc(_ socket: ScriptedGatewayWebSocket) async -> GatewayRPCClient {
        let rpc = GatewayRPCClient(socket: socket, endpoint: "ws://gw/ws")
        await rpc.start()
        return rpc
    }

    private func connectParams(_ socket: ScriptedGatewayWebSocket) async throws -> [String: Any] {
        try #require(try jsonObject(try #require(await socket.sentRequest("connect")))["params"] as? [String: Any])
    }

    private func parsedHello(_ fixture: String) throws -> GatewayHello {
        guard case .response(let response)? = GatewayFrameCodec.decode(try GatewayFixtures.text(fixture)) else {
            throw GatewayFixtures.Missing(name: "\(fixture) (not a response)")
        }
        return try GatewayHandshake.parseHello(endpoint: "ws://gw/ws", payload: response.payload)
    }

    @Test func performSignsTheChallengeAndParsesHelloOk() async throws {
        let socket = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "hello-ok-paired"]))
        let identity = try DeviceIdentity(seed: TestKeys.rfcSeed)
        let hello = try await GatewayHandshake.perform(rpc: await rpc(socket), client: client, auth: .sharedToken("shared-token"), identity: identity)

        #expect(hello.serverVersion == "2026.10.3")
        #expect(hello.scopes == TestKeys.operatorScopes)
        #expect(hello.deviceToken == "dt-test-0001")
        #expect(hello.gatewayAuthMode == "token")
        #expect(hello.gatewayHost == "ecb0a0611ff5")
        #expect(hello.methods.contains("skills.status"))

        let params = try await connectParams(socket)
        #expect(params["minProtocol"] as? Int == 4)
        #expect(params["maxProtocol"] as? Int == 4)
        #expect(params["role"] as? String == "operator")
        #expect(params["scopes"] as? [String] == TestKeys.operatorScopes)
        #expect((params["auth"] as? [String: Any])?["token"] as? String == "shared-token")
        #expect((params["auth"] as? [String: Any])?["deviceToken"] == nil)
        #expect(params["userAgent"] as? String == "ghostcrab-ios/0.3.0")
        #expect(params["locale"] as? String == "en-US")
        let clientBlock = try #require(params["client"] as? [String: Any])
        #expect(clientBlock["id"] as? String == "openclaw-ios")
        #expect(clientBlock["mode"] as? String == "ui")
        #expect(clientBlock["platform"] as? String == "ios")
        #expect(clientBlock["deviceFamily"] == nil)
        let device = try #require(params["device"] as? [String: Any])
        #expect(device["id"] as? String == TestKeys.rfcDeviceId)
        #expect(device["publicKey"] as? String == identity.publicKeyBase64URL)
        #expect(device["nonce"] as? String == "71d63897-7ebd-44e8-a27a-20ac1598b846")
        #expect(device["signedAt"] as? Int == 1_792_027_507_354)
        let expected = DeviceAuthPayload(
            deviceId: identity.deviceId, clientId: "openclaw-ios", clientMode: "ui", role: "operator",
            scopes: TestKeys.operatorScopes, signedAtMs: 1_792_027_507_354, token: "shared-token",
            nonce: "71d63897-7ebd-44e8-a27a-20ac1598b846", platform: "ios", deviceFamily: nil
        ).v3
        let signature = try #require(Base64URL.decode(try #require(device["signature"] as? String)))
        let key = try Curve25519.Signing.PublicKey(rawRepresentation: identity.publicKeyRaw)
        #expect(key.isValidSignature(signature, for: Data(expected.utf8)))
    }

    @Test func aStoredDeviceTokenIsSentWithItsScopes() async throws {
        let socket = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "hello-ok-paired"]))
        _ = try await GatewayHandshake.perform(rpc: await rpc(socket), client: client,
                                               auth: .deviceToken("dt-old", scopes: ["operator.read"]),
                                               identity: try DeviceIdentity(seed: TestKeys.rfcSeed))
        let params = try await connectParams(socket)
        #expect((params["auth"] as? [String: Any])?["deviceToken"] as? String == "dt-old")
        #expect((params["auth"] as? [String: Any])?["token"] == nil)
        #expect(params["scopes"] as? [String] == ["operator.read"])
    }

    @Test func aDeviceTokenWithoutScopesAsksForTheOperatorSet() {
        #expect(GatewayConnectAuth.deviceToken("dt", scopes: []).scopes == GatewayHandshake.operatorScopes)
    }

    @Test func noCredentialSendsNoAuthBlock() async throws {
        let identity = try DeviceIdentity(seed: TestKeys.rfcSeed)
        let none = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "hello-ok-paired"]))
        _ = try await GatewayHandshake.perform(rpc: await rpc(none), client: client, auth: .none, identity: identity)
        #expect(try await connectParams(none)["auth"] == nil)
    }

    @Test func theIOSIdentityIsTheAllowListedConstant() {
        let info = GatewayClientInfo.current()
        #expect(info.id == "openclaw-ios")
        #expect(info.mode == "ui")
        #expect(info.platform == "ios")
        #expect(info.deviceFamily == nil)
        #expect(info.displayName == "GhostCrab (iOS)")
    }

    @Test func anIdOutsideTheAllowListIsAPlainRPCRejection() async throws {
        let socket = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "err-client-id-invalid"]), closeAfterReplyTo: ["connect"])
        let channel = await rpc(socket)
        let wrongID = GatewayClientInfo(id: "ghostcrab-ios", version: "0.3.0", platform: "ios", displayName: "GhostCrab (iOS)", locale: "en-US")
        let identity = try DeviceIdentity(seed: TestKeys.rfcSeed)
        guard case let .rpc(_, _, code, _, message, _, _, _)? = await gatewayError({
            _ = try await GatewayHandshake.perform(rpc: channel, client: wrongID, auth: .sharedToken("shared-token"), identity: identity)
        }) else {
            Issue.record("expected rpc")
            return
        }
        #expect(code == "INVALID_REQUEST")
        #expect(message.contains("/client/id"))
    }

    @Test func aPlatformChangeOnAPairedDeviceAsksForReApproval() async throws {
        let socket = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "err-pairing-metadata-upgrade"]), closeAfterReplyTo: ["connect"])
        let channel = await rpc(socket)
        let identity = try DeviceIdentity(seed: TestKeys.rfcSeed)
        guard case let .pairingRequired(_, requestId, reason, _)? = await gatewayError({
            _ = try await GatewayHandshake.perform(rpc: channel, client: client, auth: .sharedToken("shared-token"), identity: identity)
        }) else {
            Issue.record("expected pairingRequired")
            return
        }
        #expect(reason == "metadata-upgrade")
        #expect(requestId == "6837c6b0-f559-445b-bc0c-a50fbe5b5998")
    }

    @Test func pairingRequiredAndTokenMismatchAreTyped() async throws {
        let identity = try DeviceIdentity(seed: TestKeys.rfcSeed)
        let pairing = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "err-pairing-required"]), closeAfterReplyTo: ["connect"])
        let pairingRPC = await rpc(pairing)
        guard case .pairingRequired(_, "af0b5d81-7bd2-4b86-80b0-4a489b541974", _, _)? = await gatewayError({
            _ = try await GatewayHandshake.perform(rpc: pairingRPC, client: client, auth: .sharedToken("t"), identity: identity)
        }) else {
            Issue.record("expected pairingRequired")
            return
        }
        let mismatch = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "err-token-mismatch"]), closeAfterReplyTo: ["connect"])
        let mismatchRPC = await rpc(mismatch)
        guard case .connectRejected(_, "AUTH_TOKEN_MISMATCH", _, _, _, _)? = await gatewayError({
            _ = try await GatewayHandshake.perform(rpc: mismatchRPC, client: client, auth: .sharedToken("wrong"), identity: identity)
        }) else {
            Issue.record("expected connectRejected")
            return
        }
    }

    @Test func aTokenOnlyHelloHasNoScopesAndNoCapabilities() throws {
        let hello = try parsedHello("hello-ok-noscope")
        #expect(hello.scopes.isEmpty)
        #expect(hello.capabilities.isEmpty)
        #expect(hello.gatewayAuthMode == nil)
        #expect(hello.gatewayHost == nil)
        #expect(hello.authRequirement(token: "shared-token") == .token)
    }

    @Test func capabilitiesFollowTheGrantedScopes() {
        let methods: Set<String> = ["config.get", "config.patch", "models.list", "skills.status"]
        func capabilities(_ scopes: [String]) -> [String] {
            GatewayHello(serverVersion: "x", methods: methods, authMethod: "token", role: "operator", scopes: scopes,
                         deviceToken: nil, gatewayAuthMode: "token", gatewayHost: nil).capabilities
        }
        #expect(capabilities(["operator.read"]) == ["models", "skills"])
        #expect(capabilities(["operator.admin"]) == ["config"])
        #expect(capabilities(TestKeys.operatorScopes) == ["config", "models", "skills"])
    }

    @Test func aChallengeWithoutTsIsAProtocolViolation() async throws {
        let socket = try ScriptedGatewayWebSocket(challenge: false)
        await socket.push(#"{"type":"event","event":"connect.challenge","payload":{"nonce":"n"}}"#)
        let channel = await rpc(socket)
        guard case .protocolViolation? = await gatewayError({
            _ = try await GatewayHandshake.perform(rpc: channel, client: client, auth: .none, identity: try DeviceIdentity(seed: TestKeys.rfcSeed))
        }) else {
            Issue.record("expected protocolViolation")
            return
        }
        #expect(await socket.sentMethods().isEmpty)
    }

    @Test(.timeLimit(.minutes(1)))
    func aMissingChallengeTimesOut() async throws {
        let socket = try ScriptedGatewayWebSocket(challenge: false)
        let channel = await rpc(socket)
        guard case .timeout? = await gatewayError({
            _ = try await GatewayHandshake.perform(rpc: channel, client: client, auth: .none,
                                                   identity: try DeviceIdentity(seed: TestKeys.rfcSeed),
                                                   challengeTimeout: .milliseconds(50))
        }) else {
            Issue.record("expected timeout")
            return
        }
    }

    @Test func anotherProtocolOrAMissingHelloIsAProtocolViolation() {
        let other = AnyCodable(["type": AnyCodable("hello-ok"), "protocol": AnyCodable(3),
                                "server": AnyCodable(["version": AnyCodable("x")] as [String: AnyCodable]),
                                "auth": AnyCodable([:] as [String: AnyCodable])] as [String: AnyCodable])
        #expect(throws: GatewayError.self) { try GatewayHandshake.parseHello(endpoint: "ws://gw/ws", payload: other) }
        #expect(throws: GatewayError.self) { try GatewayHandshake.parseHello(endpoint: "ws://gw/ws", payload: nil) }
    }

    @Test func authRequirementCapabilitiesAndCredentialOrder() throws {
        let hello = try parsedHello("hello-ok-paired")
        #expect(hello.authRequirement(token: nil) == .token)
        #expect(hello.capabilities == ["config", "models", "skills"])
        let stored = StoredDeviceToken(token: "dt", scopes: ["operator.read"], role: "operator", deviceId: TestKeys.rfcDeviceId)
        guard case .sharedToken = GatewayConnectAuth.select(sharedToken: "t", stored: stored),
              case .deviceToken = GatewayConnectAuth.select(sharedToken: nil, stored: stored),
              case .none = GatewayConnectAuth.select(sharedToken: nil, stored: nil) else {
            Issue.record("credential order differs from upstream selectGatewayConnectAuth")
            return
        }
    }

    @Test func secretsNeverReachDescriptionsOrDumps() throws {
        let hello = try parsedHello("hello-ok-paired")
        let identity = try DeviceIdentity(seed: TestKeys.rfcSeed)
        let shared = GatewayConnectAuth.sharedToken("shared-token")
        let params = try GatewayHandshake.connectParams(client: client, auth: shared, identity: identity,
                                                        challenge: ConnectChallenge(nonce: "n", ts: 1))
        let payload = DeviceAuthPayload(
            deviceId: identity.deviceId, clientId: client.id, clientMode: client.mode, role: "operator", scopes: [],
            signedAtMs: 1, token: "shared-token", nonce: "n", platform: client.platform, deviceFamily: nil
        )
        let device = GatewayConnectAuth.deviceToken("dt-test-0001", scopes: [])
        let values: [Any] = [params, params.auth as Any, shared, device, hello, payload]
        for value in values {
            var dumped = ""
            dump(value, to: &dumped)
            for rendered in [String(describing: value), String(reflecting: value), dumped] {
                #expect(!rendered.contains("shared-token"), "\(type(of: value)) leaks the shared token")
                #expect(!rendered.contains("dt-test-0001"), "\(type(of: value)) leaks the device token")
            }
        }
    }
}
