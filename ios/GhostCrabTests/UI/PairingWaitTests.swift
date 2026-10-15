import Foundation
import Testing
@testable import GhostCrab

/// What a ``PairingWait`` run reported.
@MainActor
private final class WaitRecorder {
    private(set) var prompts: [PairingPrompt] = []
    private(set) var results: [String] = []
    private(set) var tokenReads = 0

    func prompt(_ prompt: PairingPrompt) {
        prompts.append(prompt)
    }

    func finished(_ result: PairingWaitResult) {
        switch result {
        case .connected: results.append("connected")
        case .expired: results.append("expired")
        case .superseded: results.append("superseded")
        case .failed(let error): results.append("failed: \(error)")
        }
    }

    func readToken() -> String? {
        tokenReads += 1
        return "tok_saved"
    }
}

@MainActor
@Suite("Pairing wait")
struct PairingWaitTests {

    private static let url = "http://192.168.1.20:18789"
    private static let pending = GatewayError.pairingRequired(
        url: "ws://192.168.1.20:18789/ws", requestId: "req-5", reason: "not-paired", requestedScopes: ["operator.read"]
    )

    @discardableResult
    private func start(_ wait: PairingWait, _ recorder: WaitRecorder, url: String = PairingWaitTests.url) -> Bool {
        wait.start(
            url: url,
            token: { recorder.readToken() },
            onPrompt: { recorder.prompt($0) },
            onFinished: { recorder.finished($0) }
        )
    }

    @Test(.timeLimit(.minutes(1)))
    func aSecondStartWhileOneRunsIsRefused() async {
        let gateway = FakeGatewayConnectionManager(connectError: Self.pending)
        let wait = PairingWait(connectionManager: gateway, retryInterval: .seconds(60))
        let recorder = WaitRecorder()

        #expect(start(wait, recorder))
        while recorder.prompts.isEmpty { await Task.yield() }
        #expect(!start(wait, recorder))

        #expect(gateway.connectURLs == [Self.url])
        #expect(wait.isRunning)
        wait.cancel()
    }

    @Test func theTokenIsReadOnceForEveryAttempt() async {
        let gateway = FakeGatewayConnectionManager(failFirst: [Self.pending, Self.pending])
        let wait = PairingWait(connectionManager: gateway, retryInterval: .milliseconds(10))
        let recorder = WaitRecorder()

        start(wait, recorder)
        await wait.task?.value

        #expect(recorder.results == ["connected"])
        #expect(recorder.tokenReads == 1)
        #expect(gateway.connectTokens == ["tok_saved", "tok_saved", "tok_saved"])
        #expect(recorder.prompts.map(\.approveCommand) == ["openclaw devices approve req-5", "openclaw devices approve req-5"])
        #expect(!wait.isRunning)
    }

    @Test func promptsCarryTheManagersWarnings() async {
        let gateway = FakeGatewayConnectionManager(failFirst: [Self.pending], warnings: [.deviceIdentityRegenerated])
        let wait = PairingWait(connectionManager: gateway, retryInterval: .milliseconds(10))
        let recorder = WaitRecorder()

        start(wait, recorder)
        await wait.task?.value

        #expect(recorder.prompts.first?.warnings == [.deviceIdentityRegenerated])
        #expect(recorder.results == ["connected"])
    }

    @Test(.timeLimit(.minutes(1)))
    func aConnectFromAnotherScreenEndsTheWaitQuietly() async throws {
        let gateway = FakeGatewayConnectionManager(failFirst: [Self.pending])
        let wait = PairingWait(connectionManager: gateway, retryInterval: .seconds(60))
        let recorder = WaitRecorder()

        start(wait, recorder)
        while recorder.prompts.isEmpty { await Task.yield() }
        // Another screen connects somewhere else meanwhile.
        try await gateway.connect(url: "http://10.0.0.9:18789", token: nil)
        wait.checkNow()
        await wait.task?.value

        #expect(recorder.results == ["superseded"])
        #expect(gateway.connectURLs == [Self.url, "http://10.0.0.9:18789"])
        #expect(gateway.disconnectCount == 0)
    }

    @Test func aSupersededConnectEndsQuietly() async {
        let gateway = FakeGatewayConnectionManager(failFirst: [CancellationError()])
        let wait = PairingWait(connectionManager: gateway)
        let recorder = WaitRecorder()

        start(wait, recorder)
        await wait.task?.value

        #expect(recorder.results == ["superseded"])
        #expect(recorder.prompts.isEmpty)
        #expect(gateway.disconnectCount == 0)
    }

    @Test func aKeychainFailureEndsTheWaitAtOnce() async {
        let storage = GatewayError.deviceCredentialStorage(operation: "read the device identity")
        let gateway = FakeGatewayConnectionManager(failFirst: [Self.pending, storage, Self.pending])
        let wait = PairingWait(connectionManager: gateway, retryInterval: .milliseconds(10))
        let recorder = WaitRecorder()

        start(wait, recorder)
        await wait.task?.value

        #expect(recorder.results.count == 1)
        #expect(recorder.results.first?.hasPrefix("failed: ") == true)
        #expect(gateway.connectURLs.count == 2)
    }

    @Test func expiryDisconnects() async {
        let gateway = FakeGatewayConnectionManager(connectError: Self.pending)
        let wait = PairingWait(connectionManager: gateway, retryInterval: .milliseconds(10), timeout: .milliseconds(60))
        let recorder = WaitRecorder()

        start(wait, recorder)
        await wait.task?.value

        #expect(recorder.results == ["expired"])
        #expect(gateway.disconnectCount == 1)
    }

    @Test(.timeLimit(.minutes(1)))
    func cancelDisconnectsAndReportsNothing() async {
        let gateway = FakeGatewayConnectionManager(connectError: Self.pending)
        let wait = PairingWait(connectionManager: gateway, retryInterval: .seconds(60))
        let recorder = WaitRecorder()

        start(wait, recorder)
        while recorder.prompts.isEmpty { await Task.yield() }
        let running = wait.task
        wait.cancel()
        #expect(!wait.isRunning)
        await running?.value

        #expect(recorder.results.isEmpty)
        #expect(gateway.disconnectCount == 1)
        #expect(gateway.connectURLs.count == 1)
    }

    @Test(.timeLimit(.minutes(1)))
    func cancelLeavesAnotherScreensConnectionAlone() async throws {
        let gateway = FakeGatewayConnectionManager(failFirst: [Self.pending])
        let wait = PairingWait(connectionManager: gateway, retryInterval: .seconds(60))
        let recorder = WaitRecorder()

        start(wait, recorder)
        while recorder.prompts.isEmpty { await Task.yield() }
        try await gateway.connect(url: "http://10.0.0.9:18789", token: nil)
        let running = wait.task
        wait.cancel()
        await running?.value

        #expect(gateway.disconnectCount == 0)
    }

    @Test(.timeLimit(.minutes(1)))
    func releasingTheOwnerEndsTheWaitAndDisconnects() async {
        let gateway = FakeGatewayConnectionManager(connectError: Self.pending)
        var wait: PairingWait? = PairingWait(connectionManager: gateway, retryInterval: .seconds(60))
        let recorder = WaitRecorder()

        if let wait { start(wait, recorder) }
        while recorder.prompts.isEmpty { await Task.yield() }
        let running = wait?.task
        wait = nil
        await running?.value

        #expect(recorder.results.isEmpty)
        #expect(gateway.disconnectCount == 1)
    }

    @Test(.timeLimit(.minutes(1)))
    func aCheckNowLeftOverFromAnEarlierRunDoesNotCutTheNextInterval() async {
        let gateway = FakeGatewayConnectionManager(connectError: Self.pending)
        let wait = PairingWait(connectionManager: gateway, retryInterval: .seconds(60))
        let recorder = WaitRecorder()

        wait.checkNow()
        start(wait, recorder)
        while recorder.prompts.isEmpty { await Task.yield() }
        try? await Task.sleep(for: .milliseconds(300))

        #expect(gateway.connectURLs.count == 1)
        wait.cancel()
    }
}
