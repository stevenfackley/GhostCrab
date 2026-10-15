import Foundation
import Testing
@testable import GhostCrab

/// Fails the first `failures` attempts with `error`, then succeeds.
@MainActor
private final class Attempts {
    private(set) var count = 0
    private let failures: Int
    private let error: GatewayError

    init(failures: Int, error: GatewayError = PairingApprovalTests.pending) {
        self.failures = failures
        self.error = error
    }

    func run() throws {
        count += 1
        if count <= failures { throw error }
    }
}

/// Collects the prompts a loop reports.
@MainActor
private final class Prompts {
    private(set) var all: [PairingPrompt] = []

    func record(_ prompt: PairingPrompt) {
        all.append(prompt)
    }
}

@MainActor
@Suite("Pairing approval loop")
struct PairingApprovalTests {

    static let pending = GatewayError.pairingRequired(url: "ws://gw/ws", requestId: "req-9", reason: "not-paired", requestedScopes: ["operator.read"])

    @Test func thePromptNamesTheExactCommand() throws {
        let prompt = try #require(PairingPrompt(error: Self.pending))
        #expect(prompt.approveCommand == "openclaw devices approve req-9")
        #expect(prompt.requestedScopes == ["operator.read"])
        #expect(prompt.url == "ws://gw/ws")
        #expect(prompt.warnings.isEmpty)
        let anonymous = GatewayError.pairingRequired(url: "u", requestId: nil, reason: "not-paired", requestedScopes: [])
        #expect(PairingPrompt(error: anonymous)?.approveCommand == "openclaw devices list")
        #expect(PairingPrompt(error: GatewayError.timeout(url: "u")) == nil)
    }

    @Test(arguments: ["req 9", "req;rm -rf ~", "$(id)", "", String(repeating: "a", count: 129)])
    func aRequestIdThatIsNotShellSafeFallsBackToTheListCommand(requestId: String) {
        let error = GatewayError.pairingRequired(url: "u", requestId: requestId, reason: "not-paired", requestedScopes: [])
        let prompt = PairingPrompt(error: error)
        #expect(prompt?.requestId == nil)
        #expect(prompt?.approveCommand == "openclaw devices list")
    }

    @Test func aUUIDRequestIdIsKept() {
        let id = "af0b5d81-7bd2-4b86-80b0-4a489b541974"
        let prompt = PairingPrompt(url: "u", requestId: id, reason: "not-paired", requestedScopes: [])
        #expect(prompt.approveCommand == "openclaw devices approve \(id)")
    }

    @Test func aConnectThatSucceedsShowsNoPrompt() async throws {
        let prompts = Prompts()
        let attempts = Attempts(failures: 0)
        let connected = try await connectAwaitingApproval(wakeUp: PairingWakeUp(), onPrompt: { prompts.record($0) }) {
            try attempts.run()
        }
        #expect(connected)
        #expect(prompts.all.isEmpty)
        #expect(attempts.count == 1)
    }

    @Test func retriesUntilApproved() async throws {
        let prompts = Prompts()
        let attempts = Attempts(failures: 2)
        let connected = try await connectAwaitingApproval(
            wakeUp: PairingWakeUp(), onPrompt: { prompts.record($0) }, interval: .milliseconds(10), timeout: .seconds(10)
        ) {
            try attempts.run()
        }
        #expect(connected)
        #expect(attempts.count == 3)
        #expect(prompts.all.count == 2)
    }

    @Test func anyOtherFailureEndsTheWait() async {
        let attempts = Attempts(failures: 1)
        await #expect(throws: GatewayError.self) {
            _ = try await connectAwaitingApproval(wakeUp: PairingWakeUp(), onPrompt: { _ in }, interval: .milliseconds(10)) {
                try attempts.run()
                throw GatewayError.connectRejected(url: "u", detailCode: "AUTH_TOKEN_MISMATCH", message: "m", recommendedNextStep: nil)
            }
        }
        #expect(attempts.count == 2)
    }

    @Test func aKeychainFailureIsNeverRetriedByTheLoop() async {
        let attempts = Attempts(failures: 1)
        await #expect(throws: GatewayError.self) {
            _ = try await connectAwaitingApproval(wakeUp: PairingWakeUp(), onPrompt: { _ in }, interval: .milliseconds(10)) {
                try attempts.run()
                throw GatewayError.deviceCredentialStorage(operation: "read the device identity")
            }
        }
        #expect(attempts.count == 2)
    }

    @Test func noApprovalWithinTheWindowTimesOut() async throws {
        let attempts = Attempts(failures: .max)
        let connected = try await connectAwaitingApproval(
            wakeUp: PairingWakeUp(), onPrompt: { _ in }, interval: .milliseconds(10), timeout: .milliseconds(80)
        ) {
            try attempts.run()
        }
        #expect(!connected)
        #expect(attempts.count >= 2)
    }

    @Test(.timeLimit(.minutes(1)))
    func checkNowRetriesImmediately() async throws {
        let wakeUp = PairingWakeUp()
        let attempts = Attempts(failures: 1)
        let loop = Task {
            try await connectAwaitingApproval(wakeUp: wakeUp, onPrompt: { _ in }, interval: .seconds(60), timeout: .seconds(120)) {
                try attempts.run()
            }
        }
        while attempts.count < 1 { await Task.yield() }
        wakeUp.fireNow()
        #expect(try await loop.value)
        #expect(attempts.count == 2)
    }

    @Test(.timeLimit(.minutes(1)))
    func cancellingEndsTheWait() async {
        let attempts = Attempts(failures: .max)
        let loop = Task {
            try await connectAwaitingApproval(wakeUp: PairingWakeUp(), onPrompt: { _ in }, interval: .seconds(60)) {
                try attempts.run()
            }
        }
        while attempts.count < 1 { await Task.yield() }
        loop.cancel()
        await #expect(throws: CancellationError.self) { _ = try await loop.value }
    }
}
