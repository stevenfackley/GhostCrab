import Foundation
import Testing
@testable import GhostCrab

@Suite("GatewayConnectionManagerImpl (protocol 4)")
struct GatewayConnectionManagerImplTests {

    private static let clientInfo = GatewayClientInfo(
        id: "openclaw-ios", version: "0.3.0", platform: "ios", displayName: "GhostCrab (iOS)", locale: "en-US"
    )

    /// Swift Testing builds a fresh suite value per test, so every test gets its own store.
    private let tokens = InMemoryDeviceTokenStore()

    /// A gateway at a fresh `.local` URL whose `/health` answers 200 (unless `healthy` is false) and whose sockets
    /// are `sockets`, handed out in order (the last one repeats).
    private func makeManager(
        _ sockets: [ScriptedGatewayWebSocket],
        healthy: Bool = true,
        identity: FixedIdentityProvider = FixedIdentityProvider(),
        deviceTokens: (any DeviceTokenStoring)? = nil,
        challengeTimeout: Duration = GatewayHandshake.challengeTimeout,
        clock: any Clock<Duration> = ContinuousClock()
    ) -> (GatewayConnectionManagerImpl, String) {
        let base = MockURLProtocol.uniqueBaseURL()
        if healthy {
            MockURLProtocol.register(base) { _ in .init(status: 200, json: #"{"status":"ok"}"#) }
        }
        let manager = GatewayConnectionManagerImpl(
            identityProvider: identity,
            deviceTokens: deviceTokens ?? (tokens as any DeviceTokenStoring),
            clientInfo: Self.clientInfo,
            clientFactory: MockRESTFactory(),
            socketFactory: FixedSocketFactory(sockets: sockets),
            challengeTimeout: challengeTimeout,
            clock: clock
        )
        return (manager, base.absoluteString)
    }

    private func makeManager(_ socket: ScriptedGatewayWebSocket, healthy: Bool = true) -> (GatewayConnectionManagerImpl, String) {
        makeManager([socket], healthy: healthy)
    }

    private func connectFailure(_ manager: GatewayConnectionManagerImpl, _ url: String, token: String?) async -> GatewayError? {
        await gatewayError { try await manager.connect(url: url, token: token) }
    }

    private static func isConnected(_ state: GatewayConnection) -> Bool {
        if case .connected = state { return true }
        return false
    }

    /// Waits (up to ~3 s) until the manager has left `.connected`, e.g. after its session socket dropped.
    private func waitUntilNotConnected(_ manager: GatewayConnectionManagerImpl) async throws {
        for _ in 0..<300 {
            if !Self.isConnected(manager.currentState) { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        Issue.record("the manager stayed connected")
    }

    /// A socket whose handshake fails at once with a transport error (`.unreachable`): a reconnect onto it fails.
    private static func deadSocket() async throws -> ScriptedGatewayWebSocket {
        let socket = try ScriptedGatewayWebSocket(challenge: false)
        await socket.failTransport(URLError(.networkConnectionLost))
        return socket
    }

    // MARK: connect

    @Test func sharedTokenConnectSignsTheHandshakeAndLandsConnected() async throws {
        let socket = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "hello-ok-paired"]))
        let (manager, url) = makeManager(socket)
        try await manager.connect(url: url, token: "shared-token")

        guard case let .connected(stateURL, name, version, auth, isHttps, capabilities, hardware, token, reconnecting,
                                  generation) = manager.currentState else {
            Issue.record("expected connected, got \(manager.currentState)")
            return
        }
        #expect(!reconnecting)
        #expect(generation == 1)
        #expect(stateURL == url)
        #expect(name == "ecb0a0611ff5")
        #expect(version == "2026.10.3")
        #expect(auth == .token)
        #expect(!isHttps)
        #expect(capabilities == ["config", "models", "skills"])
        #expect(hardware == nil)
        #expect(token == "shared-token")
        let params = try await sentParams(socket, "connect")
        #expect((params["auth"] as? [String: Any])?["token"] as? String == "shared-token")
        #expect((params["device"] as? [String: Any])?["id"] as? String == TestKeys.rfcDeviceId)
        #expect(await manager.connectionWarnings().isEmpty)
        #expect(await socket.closeCount == 0)
    }

    @Test func theIssuedDeviceTokenIsStoredWithTheGrantedScopes() async throws {
        let (manager, url) = makeManager(try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "hello-ok-paired"])))
        try await manager.connect(url: url, token: "shared-token")
        let expected = StoredDeviceToken(token: "dt-test-0001", scopes: TestKeys.operatorScopes, role: "operator", deviceId: TestKeys.rfcDeviceId)
        #expect(tokens.load(gatewayKey: GatewayKey.of(url)) == expected)
    }

    @Test func aDeviceTokenThatCannotBeStoredIsAWarningNotAFailure() async throws {
        let socket = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "hello-ok-paired"]))
        let (manager, url) = makeManager([socket], deviceTokens: InMemoryDeviceTokenStore(failSaves: true))
        try await manager.connect(url: url, token: "shared-token")
        #expect(Self.isConnected(manager.currentState))
        #expect(await manager.connectionWarnings() == [.deviceTokenNotSaved])
        #expect(await manager.grantedScopes() == Set(TestKeys.operatorScopes))
    }

    @Test func pairingRequiredSurfacesTheRequestIdAndLandsInError() async throws {
        let socket = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "err-pairing-required"]), closeAfterReplyTo: ["connect"])
        let (manager, url) = makeManager(socket)
        guard case .pairingRequired(_, "af0b5d81-7bd2-4b86-80b0-4a489b541974", _, _)? = await connectFailure(manager, url, token: "shared-token") else {
            Issue.record("expected pairingRequired")
            return
        }
        guard case .error(_, .pairingRequired) = manager.currentState else {
            Issue.record("expected error state, got \(manager.currentState)")
            return
        }
        #expect(await socket.closeCount == 1)
        #expect(await manager.grantedScopes() == nil)
    }

    @Test func aWrongTokenIsAConnectRejectionAndClosesTheSocketOnce() async throws {
        let socket = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "err-token-mismatch"]), closeAfterReplyTo: ["connect"])
        let (manager, url) = makeManager(socket)
        guard case .connectRejected(_, "AUTH_TOKEN_MISMATCH", _, _, _, _)? = await connectFailure(manager, url, token: "wrong") else {
            Issue.record("expected connectRejected")
            return
        }
        #expect(await socket.closeCount == 1)
    }

    @Test(.timeLimit(.minutes(1)))
    func aMissingChallengeTimesOutAndClosesTheSocketOnce() async throws {
        let socket = try ScriptedGatewayWebSocket(challenge: false)
        let (manager, url) = makeManager([socket], challengeTimeout: .milliseconds(50))
        guard case .timeout? = await connectFailure(manager, url, token: "shared-token") else {
            Issue.record("expected timeout")
            return
        }
        guard case .error(_, .timeout) = manager.currentState else {
            Issue.record("expected error(timeout), got \(manager.currentState)")
            return
        }
        #expect(await socket.sentMethods().isEmpty)
        #expect(await socket.closeCount == 1)
    }

    @Test func anUnreachableGatewayNeverOpensTheSocket() async throws {
        let socket = try ScriptedGatewayWebSocket()
        let (manager, url) = makeManager(socket, healthy: false)
        guard case .unreachable? = await connectFailure(manager, url, token: nil) else {
            Issue.record("expected unreachable")
            return
        }
        #expect(await socket.sentMethods().isEmpty)
        #expect(await socket.closeCount == 0)
        guard case .error = manager.currentState else {
            Issue.record("expected error state")
            return
        }
    }

    @Test func withoutASharedTokenTheStoredDeviceTokenIsReusedWithItsScopes() async throws {
        let socket = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "hello-ok-paired"]))
        let (manager, url) = makeManager(socket)
        try tokens.save(StoredDeviceToken(token: "dt-old", scopes: ["operator.read"], role: "operator", deviceId: TestKeys.rfcDeviceId), gatewayKey: GatewayKey.of(url))
        try await manager.connect(url: url, token: nil)
        let params = try await sentParams(socket, "connect")
        #expect((params["auth"] as? [String: Any])?["deviceToken"] as? String == "dt-old")
        #expect(params["scopes"] as? [String] == ["operator.read"])
        // A new token takes this socket's live scopes.
        #expect(tokens.load(gatewayKey: GatewayKey.of(url))?.scopes == TestKeys.operatorScopes)
    }

    @Test func aDeviceTokenStoredForAnotherIdentityIsIgnored() async throws {
        let socket = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "err-token-missing"]), closeAfterReplyTo: ["connect"])
        let (manager, url) = makeManager(socket)
        try tokens.save(StoredDeviceToken(token: "dt-other", scopes: ["operator.read"], role: "operator", deviceId: "another-device"), gatewayKey: GatewayKey.of(url))
        guard case .connectRejected(_, "AUTH_TOKEN_MISSING", _, _, _, _)? = await connectFailure(manager, url, token: nil) else {
            Issue.record("expected connectRejected")
            return
        }
        #expect(try await sentParams(socket, "connect")["auth"] == nil)
    }

    @Test func aRejectedDeviceTokenIsForgotten() async throws {
        let rejection = try rejectionFrame(message: "unauthorized: device token mismatch", detailCode: "AUTH_DEVICE_TOKEN_MISMATCH")
        let socket = try ScriptedGatewayWebSocket(replies: ["connect": [rejection]])
        let (manager, url) = makeManager(socket)
        let key = GatewayKey.of(url)
        try tokens.save(StoredDeviceToken(token: "dt-revoked", scopes: TestKeys.operatorScopes, role: "operator", deviceId: TestKeys.rfcDeviceId), gatewayKey: key)
        _ = await connectFailure(manager, url, token: nil)
        #expect(tokens.load(gatewayKey: key) == nil)
        #expect(await socket.closeCount == 1)
    }

    @Test func aRegeneratedIdentityIsReportedUntilTheGatewayAcceptsIt() async throws {
        let pairing = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "err-pairing-required"]), closeAfterReplyTo: ["connect"])
        let approved = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "hello-ok-paired"]))
        let (manager, url) = makeManager([pairing, approved], identity: FixedIdentityProvider(wasRegenerated: true))
        _ = await connectFailure(manager, url, token: "shared-token")
        #expect(await manager.connectionWarnings() == [.deviceIdentityRegenerated])
        try await manager.connect(url: url, token: "shared-token")
        #expect(await manager.connectionWarnings().isEmpty)
    }

    // MARK: cancellation and supersession

    @Test(.timeLimit(.minutes(1)))
    func aCancelledHandshakeEndsDisconnectedAndClosesTheSocketOnce() async throws {
        let socket = try ScriptedGatewayWebSocket() // challenge, but `connect` is never answered
        let (manager, url) = makeManager(socket)
        let connect = Task { try await manager.connect(url: url, token: "shared-token") }
        try await waitUntilSent(socket, "connect")
        connect.cancel()
        let result = await connect.result
        #expect(throws: CancellationError.self) { try result.get() }
        guard case .disconnected = manager.currentState else {
            Issue.record("expected disconnected, got \(manager.currentState)")
            return
        }
        #expect(await socket.closeCount == 1)
    }

    @Test(.timeLimit(.minutes(1)))
    func aNewerConnectSupersedesTheOneInFlight() async throws {
        let stuck = try ScriptedGatewayWebSocket() // `connect` is never answered
        let live = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "hello-ok-paired"]))
        let (manager, url) = makeManager([stuck, live])
        let first = Task { try await manager.connect(url: url, token: "shared-token") }
        try await waitUntilSent(stuck, "connect")

        try await manager.connect(url: url, token: "shared-token")
        let firstResult = await first.result
        #expect(throws: CancellationError.self) { try firstResult.get() }
        #expect(Self.isConnected(manager.currentState))
        #expect(await stuck.closeCount == 1)
        #expect(await live.closeCount == 0)
        #expect(await manager.grantedScopes() == Set(TestKeys.operatorScopes))
    }

    @Test(.timeLimit(.minutes(1)))
    func aNewConnectReplacesTheLiveSessionAndClosesItsSocketOnce() async throws {
        let old = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "hello-ok-paired"]))
        let replacement = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "hello-ok-paired", "health": "res-health"]))
        let (manager, url) = makeManager([old, replacement])
        try await manager.connect(url: url, token: "shared-token")
        #expect(Self.isConnected(manager.currentState))

        try await manager.connect(url: url, token: "shared-token")
        #expect(Self.isConnected(manager.currentState))
        #expect(await old.closeCount == 1)
        #expect(await replacement.closeCount == 0)
        // The replacement is the live session; the old socket's end does not knock it over.
        _ = try await manager.requireSession().request("health")
        #expect(await replacement.sentMethods() == ["connect", "health"])
        #expect(await old.sentMethods() == ["connect"])
        #expect(Self.isConnected(manager.currentState))

        await manager.disconnect()
        #expect(await old.closeCount == 1)
        #expect(await replacement.closeCount == 1)
    }

    @Test(.timeLimit(.minutes(1)))
    func aDisconnectSupersedesTheConnectInFlight() async throws {
        let stuck = try ScriptedGatewayWebSocket()
        let (manager, url) = makeManager(stuck)
        let connect = Task { try await manager.connect(url: url, token: "shared-token") }
        try await waitUntilSent(stuck, "connect")

        await manager.disconnect()
        let result = await connect.result
        #expect(throws: CancellationError.self) { try result.get() }
        guard case .disconnected = manager.currentState else {
            Issue.record("expected disconnected, got \(manager.currentState)")
            return
        }
        #expect(await stuck.closeCount == 1)
    }

    // MARK: probeAuth

    @Test func probeAuthMapsAMissingTokenToTokenAndSendsNoCredential() async throws {
        let socket = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "err-token-missing"]))
        let (manager, url) = makeManager(socket)
        #expect(try await manager.probeAuth(url: url) == .token)
        #expect(try await sentParams(socket, "connect")["auth"] == nil)
        #expect(await socket.closeCount == 1)
        guard case .disconnected = manager.currentState else {
            Issue.record("the probe changed the state: \(manager.currentState)")
            return
        }
    }

    @Test func probeAuthMapsAPendingApprovalToNone() async throws {
        let socket = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "err-pairing-required"]))
        let (manager, url) = makeManager(socket)
        #expect(try await manager.probeAuth(url: url) == AuthRequirement.none)
        #expect(await socket.closeCount == 1)
    }

    @Test func probeAuthReadsTheAuthModeFromHelloOk() async throws {
        let socket = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "hello-ok-paired"]))
        let (manager, url) = makeManager(socket)
        #expect(try await manager.probeAuth(url: url) == .token)
        #expect(await socket.closeCount == 1)
        #expect(tokens.load(gatewayKey: GatewayKey.of(url)) == nil) // the probe stores nothing
    }

    // MARK: session

    @Test func grantedScopesComeFromHelloOkAndDisconnectDropsThem() async throws {
        let socket = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "hello-ok-paired", "health": "res-health"]))
        let (manager, url) = makeManager(socket)
        try await manager.connect(url: url, token: "shared-token")
        #expect(await manager.grantedScopes() == Set(TestKeys.operatorScopes))
        _ = try await manager.requireSession().request("health")
        #expect(await socket.sentMethods() == ["connect", "health"])

        await manager.disconnect()
        #expect(await manager.grantedScopes() == nil)
        #expect(await socket.closeCount == 1)
        await #expect(throws: GatewayError.self) { _ = try await manager.requireSession() }
        guard case .disconnected = manager.currentState else {
            Issue.record("expected disconnected")
            return
        }
        await manager.disconnect()
        #expect(await socket.closeCount == 1)
    }

    @Test(.timeLimit(.minutes(1)))
    func aDroppedSocketWhoseReconnectFailsEndsInTheEndCauseAndClosesEverySocketOnce() async throws {
        let socket = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "hello-ok-paired"]))
        let dead = try await Self.deadSocket()
        let (manager, url) = makeManager([socket, dead])
        try await manager.connect(url: url, token: "shared-token")
        await socket.drop()
        try await waitUntilNotConnected(manager)
        guard case .error(_, .unreachable) = manager.currentState else {
            Issue.record("expected error(unreachable), got \(manager.currentState)")
            return
        }
        #expect(await manager.grantedScopes() == nil)
        #expect(await socket.closeCount == 1)
        #expect(await dead.closeCount == 1)
        await manager.disconnect()
        #expect(await socket.closeCount == 1)
        #expect(await dead.closeCount == 1)
    }

    @Test(.timeLimit(.minutes(1)))
    func aPolicyCloseOfTheSessionSocketKeepsItsCloseCode() async throws {
        let socket = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "hello-ok-paired"]))
        let (manager, url) = makeManager([socket, try await Self.deadSocket()])
        try await manager.connect(url: url, token: "shared-token")
        await socket.drop(closeCode: 1008, reason: "device removed")
        try await waitUntilNotConnected(manager)
        guard case .error(_, .connectionClosed(_, 1008, "device removed")) = manager.currentState else {
            Issue.record("expected error(connectionClosed 1008), got \(manager.currentState)")
            return
        }
        #expect(await socket.closeCount == 1)
    }

    // MARK: silent reconnect

    @Test(.timeLimit(.minutes(1)))
    func aDroppedSocketReconnectsSilentlyWithTheStoredDeviceTokenAsANewSession() async throws {
        let first = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "hello-ok-paired"]))
        let second = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "hello-ok-paired", "health": "res-health"]))
        let (manager, url) = makeManager([first, second])
        try await manager.connect(url: url, token: "shared-token")
        var states = manager.connectionStates().makeAsyncIterator()
        let replayed = await states.next() // the live session
        #expect(replayed?.sessionGeneration == 1)

        await first.drop()

        let blip = await states.next()
        #expect(blip?.isReconnecting == true)
        #expect(blip?.sessionGeneration == 1)
        let reconnected = await states.next()
        #expect(reconnected?.isReconnecting == false)
        #expect(reconnected?.sessionGeneration == 2)
        guard case let .connected(stateURL, _, _, _, _, capabilities, _, token, _, _)? = reconnected else {
            Issue.record("expected connected, got \(String(describing: reconnected))")
            return
        }
        #expect(stateURL == url)
        #expect(capabilities == ["config", "models", "skills"])
        #expect(token == "shared-token") // the session keeps the user's token for the next connect
        // Same identity, the device token the first hello-ok issued, no shared token.
        let auth = try #require(try await sentParams(second, "connect")["auth"] as? [String: Any])
        #expect(auth["deviceToken"] as? String == "dt-test-0001")
        #expect(auth["token"] == nil)
        #expect((try await sentParams(second, "connect")["device"] as? [String: Any])?["id"] as? String == TestKeys.rfcDeviceId)
        #expect(await first.closeCount == 1)
        #expect(await second.closeCount == 0)
        _ = try await manager.requireSession().request("health")
        #expect(await second.sentMethods() == ["connect", "health"])
    }

    @Test(.timeLimit(.minutes(1)))
    func aSecondDropWithinTheRearmIntervalEndsInError() async throws {
        let clock = TestClock()
        let first = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "hello-ok-paired"]))
        let second = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "hello-ok-paired"]))
        let third = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "hello-ok-paired"]))
        let (manager, url) = makeManager([first, second, third], clock: clock)
        try await manager.connect(url: url, token: "shared-token")
        var states = manager.connectionStates().makeAsyncIterator()
        _ = await states.next()
        await first.drop()
        _ = await states.next() // reconnecting
        let reconnected = await states.next()
        #expect(reconnected?.sessionGeneration == 2)

        clock.advance(by: GatewayConnectionManagerImpl.reconnectRearmInterval - .seconds(1))
        await second.drop()

        let ended = await states.next()
        guard case .error(let errorURL, .unreachable)? = ended else {
            Issue.record("expected error(unreachable), got \(manager.currentState)")
            return
        }
        #expect(errorURL == url)
        #expect(await third.sentMethods().isEmpty) // no second reconnect
        #expect(await second.closeCount == 1)
    }

    @Test(.timeLimit(.minutes(1)))
    func aReconnectedSessionThatStayedLiveEarnsAnotherSilentReconnect() async throws {
        let clock = TestClock()
        let first = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "hello-ok-paired"]))
        let second = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "hello-ok-paired"]))
        let third = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "hello-ok-paired"]))
        let (manager, url) = makeManager([first, second, third], clock: clock)
        try await manager.connect(url: url, token: "shared-token")
        var states = manager.connectionStates().makeAsyncIterator()
        _ = await states.next()
        await first.drop()
        _ = await states.next() // reconnecting
        let reconnected = await states.next()
        #expect(reconnected?.sessionGeneration == 2)

        clock.advance(by: GatewayConnectionManagerImpl.reconnectRearmInterval)
        await second.drop()

        let blip = await states.next()
        #expect(blip?.isReconnecting == true)
        let again = await states.next()
        #expect(again?.isReconnecting == false)
        #expect(again?.sessionGeneration == 3)
        #expect(await third.sentMethods() == ["connect"])
        #expect(await second.closeCount == 1)
        #expect(await third.closeCount == 0)
    }

    @Test(.timeLimit(.minutes(1)))
    func aPairingRequiredReconnectEndsInErrorWithThePairingCause() async throws {
        let first = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "hello-ok-paired"]))
        let pairing = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "err-pairing-required"]), closeAfterReplyTo: ["connect"])
        let spare = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "hello-ok-paired"]))
        let (manager, url) = makeManager([first, pairing, spare])
        try await manager.connect(url: url, token: "shared-token")
        var states = manager.connectionStates().makeAsyncIterator()
        _ = await states.next()
        await first.drop()
        _ = await states.next() // reconnecting

        let ended = await states.next()
        guard case .error(_, .pairingRequired(_, "af0b5d81-7bd2-4b86-80b0-4a489b541974", _, _))? = ended else {
            Issue.record("expected error(pairingRequired), got \(manager.currentState)")
            return
        }
        #expect(await spare.sentMethods().isEmpty) // never retried
        #expect(await pairing.closeCount == 1) // closed before `.error` is published, so no wait is needed
        #expect(await manager.grantedScopes() == nil)
    }

    @Test(.timeLimit(.minutes(1)))
    func aDisconnectDuringTheReconnectWinsAndClosesEverySocketOnce() async throws {
        let first = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "hello-ok-paired"]))
        let stuck = try ScriptedGatewayWebSocket() // the reconnect's `connect` is never answered
        let (manager, url) = makeManager([first, stuck])
        try await manager.connect(url: url, token: "shared-token")
        await first.drop()
        try await waitUntilSent(stuck, "connect")

        await manager.disconnect()

        guard case .disconnected = manager.currentState else {
            Issue.record("expected disconnected, got \(manager.currentState)")
            return
        }
        // The abandoned reconnect fails once its socket is closed; it must publish nothing and close nothing again.
        for _ in 0..<20 { await Task.yield() }
        try await Task.sleep(for: .milliseconds(50))
        guard case .disconnected = manager.currentState else {
            Issue.record("the abandoned reconnect published \(manager.currentState)")
            return
        }
        #expect(await first.closeCount == 1)
        #expect(await stuck.closeCount == 1)
        #expect(await manager.grantedScopes() == nil)
        await #expect(throws: GatewayError.self) { _ = try await manager.requireSession() }
    }
}
