import Testing
import Foundation
@testable import GhostCrab

/// Never answers: a request stays in flight until its task is cancelled,
/// which is how Back on Manual Entry mid-connect looks to URLSession.
private final class NeverRespondingURLProtocol: URLProtocol, @unchecked Sendable {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {}
    override func stopLoading() {}
}

private struct NeverRespondingClientFactory: OpenClawAPIClientFactory {
    func unauthenticated(baseURL: URL, allowCleartextPublicIPs: Bool) -> OpenClawAPIClient {
        OpenClawAPIClient.makeForTesting(
            baseURL: baseURL,
            allowCleartextPublicIPs: allowCleartextPublicIPs,
            protocolClasses: [NeverRespondingURLProtocol.self]
        )
    }

    func authenticated(baseURL: URL, token: String, allowCleartextPublicIPs: Bool) -> OpenClawAPIClient {
        OpenClawAPIClient.makeForTesting(
            baseURL: baseURL,
            token: token,
            allowCleartextPublicIPs: allowCleartextPublicIPs,
            protocolClasses: [NeverRespondingURLProtocol.self]
        )
    }
}

@Suite("GatewayConnectionManagerImpl cancellation")
struct GatewayConnectionManagerCancellationTests {

    /// Android parity (`GatewayConnectionManagerImpl.kt`, CancellationException
    /// branch): a caller that goes away mid-handshake leaves the manager
    /// `.disconnected`, never `.error`, so the picker shows no
    /// "Error connecting to …" for a connect the user abandoned.
    @Test(.timeLimit(.minutes(1)))
    func cancelledConnectEndsDisconnectedWithoutAnError() async {
        let manager = GatewayConnectionManagerImpl(clientFactory: NeverRespondingClientFactory())
        let url = MockURLProtocol.uniqueBaseURL().absoluteString

        let connect = Task { try await manager.connect(url: url, token: nil) }
        // Wait for the handshake to start (it then hangs on the protocol above).
        while Self.isDisconnected(manager.currentState) {
            await Task.yield()
        }
        #expect(Self.isConnecting(manager.currentState))
        connect.cancel()
        let result = await connect.result

        #expect(throws: CancellationError.self) { try result.get() }
        guard case .disconnected = manager.currentState else {
            Issue.record("expected .disconnected, got \(manager.currentState)")
            return
        }
    }

    private static func isConnecting(_ state: GatewayConnection) -> Bool {
        if case .connecting = state { return true }
        return false
    }

    private static func isDisconnected(_ state: GatewayConnection) -> Bool {
        if case .disconnected = state { return true }
        return false
    }
}
