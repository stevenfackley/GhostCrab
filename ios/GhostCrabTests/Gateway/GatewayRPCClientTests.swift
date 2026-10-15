import Foundation
import Testing
@testable import GhostCrab

@Suite("GatewayRPCClient")
struct GatewayRPCClientTests {

    private struct OuterTimeout: Error {}
    private struct SendBroke: Error {}
    private struct NeverSent: Error, CustomStringConvertible {
        let method: String
        var description: String { "the client never sent \(method)" }
    }

    private func client(_ socket: ScriptedGatewayWebSocket, timeout: Duration = .seconds(5)) async -> GatewayRPCClient {
        let rpc = GatewayRPCClient(socket: socket, endpoint: "ws://test/ws", defaultTimeout: timeout)
        await rpc.start()
        return rpc
    }

    /// Waits until the client has put a `method` request on the socket.
    /// - Returns: That request's id.
    private func sentId(_ socket: ScriptedGatewayWebSocket, _ method: String) async throws -> String {
        for _ in 0..<300 {
            if let id = await socket.sentId(method) { return id }
            try await Task.sleep(for: .milliseconds(10))
        }
        throw NeverSent(method: method)
    }

    /// A caller's own timeout: races `body` against `limit` and cancels the loser.
    private func withOuterTimeout<T: Sendable>(
        _ limit: Duration,
        _ body: @escaping @Sendable () async throws -> T
    ) async throws -> T {
        try await withThrowingTaskGroup(of: T.self) { group in
            group.addTask { try await body() }
            group.addTask {
                try await Task.sleep(for: limit)
                throw OuterTimeout()
            }
            defer { group.cancelAll() }
            guard let first = try await group.next() else { throw OuterTimeout() }
            return first
        }
    }

    // MARK: - Correlation

    @Test func requestCorrelatesTheResponseByStringId() async throws {
        let socket = try ScriptedGatewayWebSocket(replies: try .fixtures(["health": "res-health"]))
        let payload = try await client(socket).request("health")
        #expect(payload?.objectValue?["ok"]?.value as? Bool == true)
        let sent = try jsonObject(try #require(await socket.sentRequest("health")))
        #expect(sent["type"] as? String == "req")
        #expect((sent["id"] as? String)?.hasPrefix("gc-") == true)
    }

    @Test func concurrentRequestsAnsweredOutOfOrderEachGetTheirOwnResponse() async throws {
        let socket = try ScriptedGatewayWebSocket()
        let rpc = await client(socket)
        let first = Task { try await rpc.request("first") }
        let firstId = try await sentId(socket, "first")
        let second = Task { try await rpc.request("second") }
        let secondId = try await sentId(socket, "second")
        #expect(firstId != secondId)
        await socket.push(#"{"type":"res","id":"\#(secondId)","ok":true,"payload":{"n":2}}"#)
        await socket.push(#"{"type":"res","id":"\#(firstId)","ok":true,"payload":{"n":1}}"#)
        #expect(try await second.value?.objectValue?["n"]?.value as? Int == 2)
        #expect(try await first.value?.objectValue?["n"]?.value as? Int == 1)
        #expect(await rpc.pendingRequestCount == 0)
    }

    @Test func aResponseWithAnUnknownIdIsIgnored() async throws {
        let socket = try ScriptedGatewayWebSocket()
        let rpc = await client(socket)
        let request = Task { try await rpc.request("health") }
        let id = try await sentId(socket, "health")
        await socket.push(#"{"type":"res","id":"gc-999","ok":true,"payload":{"stray":true}}"#)
        await socket.push(#"{"type":"res","id":"\#(id)","ok":true,"payload":{"ok":true}}"#)
        let payload = try await request.value
        #expect(payload?.objectValue?["ok"]?.value as? Bool == true)
        #expect(payload?.objectValue?["stray"] == nil)
        #expect(await rpc.isClosed == false)
    }

    @Test func okFalseBecomesTheTypedError() async throws {
        let socket = try ScriptedGatewayWebSocket(replies: try .fixtures(["skills.list": "err-unknown-method", "status": "err-missing-scope"]))
        let rpc = await client(socket)
        guard case .methodUnsupported(_, "skills.list")? = await gatewayError({ _ = try await rpc.request("skills.list") }) else {
            Issue.record("expected methodUnsupported")
            return
        }
        guard case .missingScope(_, _, "operator.read")? = await gatewayError({ _ = try await rpc.request("status") }) else {
            Issue.record("expected missingScope")
            return
        }
    }

    // MARK: - Captured refusals

    @Test func aMetadataUpgradeRePairingReachesTheCallerAsPairingRequired() async throws {
        let socket = try ScriptedGatewayWebSocket(
            replies: try .fixtures(["connect": "err-pairing-metadata-upgrade"]),
            closeAfterReplyTo: ["connect"]
        )
        let rpc = await client(socket)
        let error = await gatewayError { _ = try await rpc.request("connect") }
        guard case let .pairingRequired(_, requestId, reason, scopes)? = error else {
            Issue.record("expected pairingRequired, got \(String(describing: error))")
            return
        }
        #expect(requestId == "6837c6b0-f559-445b-bc0c-a50fbe5b5998")
        #expect(reason == "metadata-upgrade")
        #expect(scopes == TestKeys.operatorScopes)
        #expect(error?.isRetryable == true)
    }

    @Test func aRefusedClientIdIsAnsweredBeforeThePolicyClose() async throws {
        let socket = try ScriptedGatewayWebSocket(
            replies: try .fixtures(["connect": "err-client-id-invalid"]),
            closeAfterReplyTo: ["connect"]
        )
        let rpc = await client(socket)
        let error = await gatewayError { _ = try await rpc.request("connect") }
        guard case let .rpc(_, method, code, _, message, _, _, _)? = error else {
            Issue.record("expected rpc, got \(String(describing: error))")
            return
        }
        #expect(method == "connect")
        #expect(code == "INVALID_REQUEST")
        #expect(message.contains("/client/id"))
        #expect(error?.isRetryable == false)

        let later = await gatewayError { _ = try await rpc.request("health") }
        guard case let .connectionClosed(_, closeCode, reason)? = later else {
            Issue.record("expected connectionClosed after the 1008 close, got \(String(describing: later))")
            return
        }
        #expect(closeCode == 1008)
        #expect(reason.contains("/client/id"))
    }

    // MARK: - Challenge

    @Test func aChallengeThatArrivedEarlyIsStillReturned() async throws {
        let rpc = await client(try ScriptedGatewayWebSocket())
        try await Task.sleep(for: .milliseconds(20))
        #expect(try await rpc.awaitChallenge().event == GatewayRPCClient.connectChallengeEvent)
    }

    @Test func noChallengeTimesOut() async throws {
        let rpc = await client(try ScriptedGatewayWebSocket(challenge: false))
        guard case .timeout(let url, _)? = await gatewayError({ _ = try await rpc.awaitChallenge(timeout: .milliseconds(50)) }) else {
            Issue.record("expected timeout")
            return
        }
        #expect(url == "ws://test/ws#connect.challenge")
    }

    @Test func aCancelledChallengeWaitThrowsCancellation() async throws {
        let rpc = await client(try ScriptedGatewayWebSocket(challenge: false))
        let waiter = Task { () -> (any Error)? in
            do {
                _ = try await rpc.awaitChallenge()
                return nil
            } catch {
                return error
            }
        }
        try await Task.sleep(for: .milliseconds(20))
        waiter.cancel()
        #expect(await waiter.value is CancellationError)
    }

    // MARK: - Timeouts and cancellation

    @Test func anUnansweredRequestTimesOut() async throws {
        let rpc = await client(try ScriptedGatewayWebSocket(), timeout: .milliseconds(50))
        guard case .timeout(let url, _)? = await gatewayError({ _ = try await rpc.request("health") }) else {
            Issue.record("expected timeout")
            return
        }
        #expect(url == "ws://test/ws#health")
        #expect(await rpc.pendingRequestCount == 0)
    }

    @Test func callerCancellationMidRequestRemovesThePendingEntry() async throws {
        let socket = try ScriptedGatewayWebSocket()
        let rpc = await client(socket)
        let caller = Task { () -> (any Error)? in
            do {
                _ = try await rpc.request("health")
                return nil
            } catch {
                return error
            }
        }
        _ = try await sentId(socket, "health")
        #expect(await rpc.pendingRequestCount == 1)
        caller.cancel()
        #expect(await caller.value is CancellationError)
        #expect(await rpc.pendingRequestCount == 0)
        #expect(await rpc.isClosed == false)
    }

    @Test func anOuterTimeoutPropagatesAsItselfNotAsAGatewayTimeout() async throws {
        let rpc = await client(try ScriptedGatewayWebSocket(), timeout: .seconds(5))
        await #expect(throws: OuterTimeout.self) {
            try await withOuterTimeout(.milliseconds(50)) { try await rpc.request("health") }
        }
        #expect(await rpc.pendingRequestCount == 0)
    }

    @Test func aShorterRequestTimeoutInsideALongerOuterTimeoutIsAGatewayTimeout() async throws {
        let rpc = await client(try ScriptedGatewayWebSocket(), timeout: .seconds(5))
        let error = await gatewayError {
            _ = try await withOuterTimeout(.seconds(5)) { try await rpc.request("health", timeout: .milliseconds(50)) }
        }
        guard case .timeout(let url, _)? = error else {
            Issue.record("expected the request's own timeout, got \(String(describing: error))")
            return
        }
        #expect(url == "ws://test/ws#health")
        #expect(await rpc.pendingRequestCount == 0)
    }

    // MARK: - Socket end

    @Test func aDroppedSocketFailsPendingAndLaterRequests() async throws {
        let socket = try ScriptedGatewayWebSocket()
        let rpc = await client(socket)
        let pending = Task { await gatewayError { _ = try await rpc.request("health") } }
        try await Task.sleep(for: .milliseconds(20))
        await socket.drop()
        guard case .unreachable? = await pending.value else {
            Issue.record("expected unreachable for the pending request")
            return
        }
        guard case .unreachable? = await gatewayError({ _ = try await rpc.request("status") }) else {
            Issue.record("expected unreachable after close")
            return
        }
        #expect(await rpc.isClosed)
    }

    @Test func aPolicyCloseWithoutAResponseIsANonRetryableCloseError() async throws {
        let socket = try ScriptedGatewayWebSocket()
        let rpc = await client(socket)
        let pending = Task { await gatewayError { _ = try await rpc.request("health") } }
        _ = try await sentId(socket, "health")
        await socket.drop(closeCode: 1008, reason: "invalid request frame")
        let error = await pending.value
        guard case let .connectionClosed(url, closeCode, reason)? = error else {
            Issue.record("expected connectionClosed, got \(String(describing: error))")
            return
        }
        #expect(url == "ws://test/ws")
        #expect(closeCode == 1008)
        #expect(reason == "invalid request frame")
        #expect(error?.isRetryable == false)
        guard case .connectionClosed? = await gatewayError({ _ = try await rpc.request("status") }) else {
            Issue.record("expected connectionClosed for a later request")
            return
        }
    }

    @Test func anyOtherCloseIsUnreachable() async throws {
        let socket = try ScriptedGatewayWebSocket()
        let rpc = await client(socket)
        let pending = Task { await gatewayError { _ = try await rpc.request("health") } }
        _ = try await sentId(socket, "health")
        await socket.drop(closeCode: 1001, reason: "going away")
        guard case .unreachable? = await pending.value else {
            Issue.record("expected unreachable")
            return
        }
    }

    @Test func aTransportErrorFailsPendingRequestsWithTheErrorAsCause() async throws {
        let socket = try ScriptedGatewayWebSocket()
        let rpc = await client(socket)
        let pending = Task { await gatewayError { _ = try await rpc.request("health") } }
        _ = try await sentId(socket, "health")
        await socket.failTransport(URLError(.networkConnectionLost))
        guard case let .unreachable(url, underlying)? = await pending.value else {
            Issue.record("expected unreachable")
            return
        }
        #expect(url == "ws://test/ws")
        #expect((underlying as? URLError)?.code == .networkConnectionLost)
        #expect(await rpc.isClosed)
    }

    @Test func aSendFailureSurfacesAsUnreachableWithTheCause() async throws {
        let rpc = await client(try ScriptedGatewayWebSocket(failSendsWith: SendBroke()))
        let error = await gatewayError { _ = try await rpc.request("health") }
        guard case let .unreachable(url, underlying)? = error else {
            Issue.record("expected unreachable, got \(String(describing: error))")
            return
        }
        #expect(url == "ws://test/ws")
        #expect(underlying is SendBroke)
        #expect(await rpc.pendingRequestCount == 0)
        #expect(await rpc.isClosed == false)
    }

    @Test func closeFailsEveryPendingRequestAndLaterOnesWithTheTypedError() async throws {
        let socket = try ScriptedGatewayWebSocket(challenge: false)
        let rpc = await client(socket)
        let events = await rpc.events()
        let pending = Task { await gatewayError { _ = try await rpc.request("health") } }
        let challenge = Task { await gatewayError { _ = try await rpc.awaitChallenge() } }
        _ = try await sentId(socket, "health")
        await rpc.close()
        guard case .unreachable(let url, _)? = await pending.value else {
            Issue.record("expected unreachable for the pending request")
            return
        }
        #expect(url == "ws://test/ws")
        guard case .unreachable? = await challenge.value else {
            Issue.record("expected unreachable for the challenge wait")
            return
        }
        guard case .unreachable? = await gatewayError({ _ = try await rpc.request("status") }) else {
            Issue.record("expected unreachable after close")
            return
        }
        var iterator = events.makeAsyncIterator()
        let finished = await iterator.next() == nil
        #expect(finished)
        #expect(await rpc.pendingRequestCount == 0)
        #expect(await rpc.isClosed)
    }

    @Test func closeClosesTheSocket() async throws {
        let socket = try ScriptedGatewayWebSocket()
        await client(socket).close()
        #expect(await socket.closedByClient)
    }

    // MARK: - Events

    @Test func serverEventsReachSubscribers() async throws {
        let socket = try ScriptedGatewayWebSocket()
        let rpc = await client(socket)
        let events = await rpc.events()
        await socket.push(#"{"type":"event","event":"skills.changed","payload":{}}"#)
        var iterator = events.makeAsyncIterator()
        var names: [String] = []
        while let event = await iterator.next() {
            names.append(event.event)
            if event.event == "skills.changed" { break }
        }
        #expect(names.contains("skills.changed"))
    }

    @Test func eventsFinishWhenTheSocketEnds() async throws {
        let socket = try ScriptedGatewayWebSocket(challenge: false)
        let rpc = await client(socket)
        let events = await rpc.events()
        await socket.push(#"{"type":"event","event":"tick","payload":{}}"#)
        await socket.drop()
        var names: [String] = []
        for await event in events {
            names.append(event.event)
        }
        #expect(names == ["tick"])

        var late = await rpc.events().makeAsyncIterator()
        let finished = await late.next() == nil
        #expect(finished)
    }

    @Test func anEventThatPrecedesItsResponseReachesASubscriberThatSubscribedFirst() async throws {
        let socket = try ScriptedGatewayWebSocket(
            replies: try .fixtures(["skills.update": "res-skills-update"]),
            eventsBeforeReply: ["skills.update": [try GatewayFixtures.text("evt-config-changed")]]
        )
        let rpc = await client(socket)
        let events = await rpc.events()
        let payload = try await rpc.request("skills.update")
        #expect(payload?.objectValue?["skillKey"]?.value as? String == "browser-automation")
        // Already buffered: the event was dispatched before the response. Skips a possible connect.challenge.
        var iterator = events.makeAsyncIterator()
        while let event = await iterator.next() {
            if event.event == "config.changed" {
                #expect(event.seq == 2)
                return
            }
        }
        Issue.record("config.changed never reached the subscriber")
    }
}
