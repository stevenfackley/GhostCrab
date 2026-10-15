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
        let wait = PairingWait(connectionManager: gateway, retryInterval: .seconds(60), clock: TestClock())
        let recorder = WaitRecorder()

        #expect(start(wait, recorder))
        while recorder.prompts.isEmpty { await Task.yield() }
        #expect(!start(wait, recorder))

        #expect(gateway.connectURLs == [Self.url])
        #expect(wait.isRunning)
        wait.cancel()
    }

    @Test(.timeLimit(.minutes(1)))
    func theTokenIsReadOnceForEveryAttempt() async {
        let gateway = FakeGatewayConnectionManager(failFirst: [Self.pending, Self.pending])
        let wait = PairingWait(connectionManager: gateway, clock: ImmediateClock())
        let recorder = WaitRecorder()

        start(wait, recorder)
        await wait.task?.value

        #expect(recorder.results == ["connected"])
        #expect(recorder.tokenReads == 1)
        #expect(gateway.connectTokens == ["tok_saved", "tok_saved", "tok_saved"])
        #expect(recorder.prompts.map(\.approveCommand) == ["openclaw devices approve req-5", "openclaw devices approve req-5"])
        #expect(!wait.isRunning)
    }

    @Test(.timeLimit(.minutes(1)))
    func promptsCarryTheManagersWarnings() async {
        let gateway = FakeGatewayConnectionManager(failFirst: [Self.pending], warnings: [.deviceIdentityRegenerated])
        let wait = PairingWait(connectionManager: gateway, clock: ImmediateClock())
        let recorder = WaitRecorder()

        start(wait, recorder)
        await wait.task?.value

        #expect(recorder.prompts.first?.warnings == [.deviceIdentityRegenerated])
        #expect(recorder.results == ["connected"])
    }

    @Test(.timeLimit(.minutes(1)))
    func aConnectFromAnotherScreenEndsTheWaitQuietly() async throws {
        let gateway = FakeGatewayConnectionManager(failFirst: [Self.pending])
        let wait = PairingWait(connectionManager: gateway, retryInterval: .seconds(60), clock: TestClock())
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

    @Test(.timeLimit(.minutes(1)))
    func aKeychainFailureEndsTheWaitAtOnce() async {
        let storage = GatewayError.deviceCredentialStorage(operation: "read the device identity")
        let gateway = FakeGatewayConnectionManager(failFirst: [Self.pending, storage, Self.pending])
        let wait = PairingWait(connectionManager: gateway, clock: ImmediateClock())
        let recorder = WaitRecorder()

        start(wait, recorder)
        await wait.task?.value

        #expect(recorder.results.count == 1)
        #expect(recorder.results.first?.hasPrefix("failed: ") == true)
        #expect(gateway.connectURLs.count == 2)
    }

    /// Retries at 5 s, 10 s and 12 s (the end of the window), then expires.
    @Test(.timeLimit(.minutes(1)))
    func expiryDisconnects() async {
        let gateway = FakeGatewayConnectionManager(connectError: Self.pending)
        let wait = PairingWait(connectionManager: gateway, retryInterval: .seconds(5), timeout: .seconds(12), clock: ImmediateClock())
        let recorder = WaitRecorder()

        start(wait, recorder)
        await wait.task?.value

        #expect(recorder.results == ["expired"])
        #expect(gateway.disconnectCount == 1)
        #expect(gateway.connectURLs.count == 4)
    }

    @Test(.timeLimit(.minutes(1)))
    func cancelDisconnectsAndReportsNothing() async {
        let gateway = FakeGatewayConnectionManager(connectError: Self.pending)
        let wait = PairingWait(connectionManager: gateway, retryInterval: .seconds(60), clock: TestClock())
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
        let wait = PairingWait(connectionManager: gateway, retryInterval: .seconds(60), clock: TestClock())
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
        var wait: PairingWait? = PairingWait(connectionManager: gateway, retryInterval: .seconds(60), clock: TestClock())
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
        let clock = TestClock()
        let gateway = FakeGatewayConnectionManager(connectError: Self.pending)
        let wait = PairingWait(connectionManager: gateway, retryInterval: .seconds(60), clock: clock)
        let recorder = WaitRecorder()

        wait.checkNow()
        start(wait, recorder)
        // A cut interval would retry at once, so the run would first sleep after a second connect.
        await clock.waitForSleepers()

        #expect(gateway.connectURLs.count == 1)
        wait.cancel()
    }

    @Test(.timeLimit(.minutes(1)))
    func theNextConnectWaitsForTheIntervalOnTheClock() async {
        let clock = TestClock()
        let gateway = FakeGatewayConnectionManager(failFirst: [Self.pending])
        let wait = PairingWait(connectionManager: gateway, retryInterval: .seconds(5), clock: clock)
        let recorder = WaitRecorder()

        start(wait, recorder)
        await clock.waitForSleepers()
        clock.advance(by: .seconds(4))
        #expect(clock.sleeperCount == 1)
        #expect(gateway.connectURLs.count == 1)

        clock.advance(by: .seconds(1))
        await wait.task?.value
        #expect(recorder.results == ["connected"])
        #expect(gateway.connectURLs.count == 2)
    }

    /// The gateway refuses a connect that was in flight when Cancel was tapped: the stale `PAIRING_REQUIRED` is
    /// disconnected and nothing is shown.
    @Test(.timeLimit(.minutes(1)))
    func cancelMidConnectDisconnectsTheRefusedConnect() async {
        let gateway = GatedConnectionManager(FakeGatewayConnectionManager(connectError: Self.pending))
        let wait = PairingWait(connectionManager: gateway, retryInterval: .seconds(60), clock: TestClock())
        let recorder = WaitRecorder()

        start(wait, recorder)
        await gateway.waitForConnects(1)
        let running = wait.task
        wait.cancel()
        gateway.release()
        await running?.value

        #expect(recorder.results.isEmpty)
        #expect(recorder.prompts.isEmpty)
        #expect(gateway.inner.connectURLs == [Self.url])
        #expect(gateway.inner.disconnectCount == 1)
    }

    /// The gateway accepts the retry that was in flight when Cancel was tapped: no screen shows that session, so the
    /// run disconnects it and reports nothing.
    @Test(.timeLimit(.minutes(1)))
    func cancelWhileTheRetryIsAcceptedDisconnectsThatSession() async {
        let clock = TestClock()
        let gateway = GatedConnectionManager(FakeGatewayConnectionManager(failFirst: [Self.pending]))
        let wait = PairingWait(connectionManager: gateway, retryInterval: .seconds(60), clock: clock)
        let recorder = WaitRecorder()

        start(wait, recorder)
        await gateway.waitForConnects(1)
        gateway.release()
        await clock.waitForSleepers()
        wait.checkNow()
        await gateway.waitForConnects(2)
        let running = wait.task
        wait.cancel()
        gateway.release()
        await running?.value

        #expect(recorder.results.isEmpty)
        #expect(recorder.prompts.count == 1)
        #expect(gateway.inner.connectURLs == [Self.url, Self.url])
        #expect(gateway.inner.disconnectCount == 1)
        #expect(!wait.isRunning)
    }
}

/// Holds every `connect` until ``release()``, then lets the wrapped fake answer it: a connect still in flight while
/// the test acts (taps Cancel). Like a real connect, a held one does not stop for task cancellation.
private final class GatedConnectionManager: GatewayConnectionManager, @unchecked Sendable {
    let inner: FakeGatewayConnectionManager
    private let lock = NSLock()
    private var held: [CheckedContinuation<Void, Never>] = []
    private var started = 0

    init(_ inner: FakeGatewayConnectionManager) {
        self.inner = inner
    }

    /// Lets every held connect through to the wrapped fake.
    func release() {
        let released: [CheckedContinuation<Void, Never>] = lock.withLock {
            defer { held = [] }
            return held
        }
        for continuation in released { continuation.resume() }
    }

    /// Returns once `count` connects have started, yielding (never sleeping) meanwhile.
    func waitForConnects(_ count: Int) async {
        while lock.withLock({ started }) < count { await Task.yield() }
    }

    var currentState: GatewayConnection { inner.currentState }

    func connectionStates() -> AsyncStream<GatewayConnection> { inner.connectionStates() }

    func probeAuth(url: String) async throws -> AuthRequirement { try await inner.probeAuth(url: url) }

    func connect(url: String, token: String?) async throws {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            lock.withLock {
                started += 1
                held.append(continuation)
            }
        }
        try await inner.connect(url: url, token: token)
    }

    func disconnect() async { await inner.disconnect() }

    func connectionWarnings() async -> [ConnectionWarning] { await inner.connectionWarnings() }
}
