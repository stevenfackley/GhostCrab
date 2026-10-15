import Foundation
import Testing
@testable import GhostCrab

@Suite("AIRecommendationServiceImpl")
struct AIRecommendationServiceImplTests {

    private let context = RecommendationContext(activeConfig: OpenClawConfig(sections: [:]), hardwareInfo: nil, activeModelId: nil)

    @Test func neverAvailableEvenWhenConnected() async throws {
        let gateway = FakeGatewayConnectionManager()
        try await gateway.connect(url: "http://192.168.1.50:18789", token: nil)
        #expect(await AIRecommendationServiceImpl(connectionManager: gateway).isAvailable() == false)
    }

    @Test func aRecommendationIsUnavailableAtTheConnectedGateway() async throws {
        let gateway = FakeGatewayConnectionManager()
        try await gateway.connect(url: "http://192.168.1.50:18789", token: nil)
        let service = AIRecommendationServiceImpl(connectionManager: gateway)
        guard case .aiServiceUnavailable("http://192.168.1.50:18789")? = await gatewayError({
            _ = try await service.getRecommendation(query: "q", context: context)
        }) else {
            Issue.record("expected aiServiceUnavailable")
            return
        }
    }

    @Test func disconnectedIsUnreachable() async {
        let service = AIRecommendationServiceImpl(connectionManager: FakeGatewayConnectionManager())
        guard case .unreachable? = await gatewayError({ _ = try await service.getRecommendation(query: "q", context: context) }) else {
            Issue.record("expected unreachable")
            return
        }
    }
}
