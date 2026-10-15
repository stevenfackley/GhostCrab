import Testing
@testable import GhostCrab

@MainActor
@Suite("DashboardViewModel")
struct DashboardViewModelTests {

    @Test func secondDisconnectIsIgnored() async {
        let gateway = FakeGatewayConnectionManager()
        let vm = DashboardViewModel(gateway: gateway, models: FakeModelRepository(models: []), ai: FakeAIRecommendationService())

        let first = await vm.disconnect()
        let second = await vm.disconnect()

        #expect(first)
        #expect(!second)
        #expect(vm.isDisconnecting)
        #expect(gateway.disconnectCount == 1)
    }

    @Test func repeatedAppearDoesNotStackLoads() async {
        let models = FakeModelRepository(models: [FakeModelRepository.model("m1", active: true)])
        let vm = DashboardViewModel(gateway: FakeGatewayConnectionManager(), models: models, ai: FakeAIRecommendationService(available: true))

        vm.onAppear()
        vm.onAppear()
        await vm.loadTask?.value
        await vm.aiTask?.value

        #expect(models.getModelsCount == 1)
        #expect(vm.activeModel?.id == "m1")
        #expect(vm.aiAvailable)
        #expect(vm.loadTask == nil)
        #expect(vm.aiTask == nil)
    }

    @Test func aDeviceTokenThatCouldNotBeSavedIsShown() async {
        let gateway = FakeGatewayConnectionManager(warnings: [.deviceTokenNotSaved])
        let vm = DashboardViewModel(gateway: gateway, models: FakeModelRepository(models: []), ai: FakeAIRecommendationService())

        vm.onAppear()
        await vm.loadTask?.value

        #expect(vm.warnings == [.deviceTokenNotSaved])
        #expect(ConnectionWarning.deviceTokenNotSaved.userMessage.contains("gateway token"))
    }

    @Test func noWarningsShowNothing() async {
        let vm = DashboardViewModel(gateway: FakeGatewayConnectionManager(), models: FakeModelRepository(models: []), ai: FakeAIRecommendationService())

        vm.onAppear()
        await vm.loadTask?.value

        #expect(vm.warnings.isEmpty)
    }

    @Test func leavingCancelsTheLoadAndReturningReloads() async {
        let models = FakeModelRepository(models: [FakeModelRepository.model("m1", active: true)])
        let vm = DashboardViewModel(gateway: FakeGatewayConnectionManager(), models: models, ai: FakeAIRecommendationService())

        vm.onAppear()
        let abandoned = vm.loadTask
        vm.onDisappear()
        #expect(vm.loadTask == nil)
        await abandoned?.value
        // The cancelled load applies nothing.
        #expect(vm.activeModel == nil)
        #expect(vm.lastError == nil)

        vm.onAppear()
        await vm.loadTask?.value

        #expect(vm.activeModel?.id == "m1")
        #expect(vm.modelCount == 1)
    }

    // MARK: - Silent reconnect and dropped sessions

    @Test func theReconnectingStateChangesNothingAndTheNewSessionReloads() async {
        let gateway = ScriptedConnectionManager(ScriptedConnectionManager.connected(generation: 1))
        let models = FakeModelRepository(models: [FakeModelRepository.model("m1", active: true)])
        let vm = DashboardViewModel(gateway: gateway, models: models, ai: FakeAIRecommendationService())
        vm.onAppear()
        await vm.loadTask?.value
        await waitUntil { gateway.subscriberCount == 1 }
        #expect(models.getModelsCount == 1)

        gateway.emit(ScriptedConnectionManager.connected(generation: 1, reconnecting: true))
        await waitUntil { vm.connection.isReconnecting }
        await vm.loadTask?.value // a reload the reconnecting state started, if any

        #expect(models.getModelsCount == 1)
        #expect(vm.endCause == nil)
        #expect(vm.lastError == nil)
        #expect(vm.activeModel?.id == "m1")
        #expect(vm.unavailableReason(.config) == nil) // the lost session's capabilities hold meanwhile

        gateway.emit(ScriptedConnectionManager.connected(generation: 2))
        await waitUntil { vm.connection.sessionGeneration == 2 && !vm.connection.isReconnecting }
        await vm.loadTask?.value

        #expect(models.getModelsCount == 2)
        #expect(vm.activeModel?.id == "m1")
        vm.onDisappear()
    }

    @Test func theWarningsAreReReadOnEveryStateChange() async {
        let gateway = ScriptedConnectionManager(ScriptedConnectionManager.connected(generation: 1))
        let vm = DashboardViewModel(gateway: gateway, models: FakeModelRepository(models: []), ai: FakeAIRecommendationService())
        vm.onAppear()
        await vm.loadTask?.value
        await waitUntil { gateway.subscriberCount == 1 }
        #expect(vm.warnings.isEmpty)

        gateway.setWarnings([.deviceTokenNotSaved])
        gateway.emit(ScriptedConnectionManager.connected(generation: 1, reconnecting: true))
        await waitUntil { vm.warnings == [.deviceTokenNotSaved] }

        gateway.setWarnings([])
        gateway.emit(.error(url: ScriptedConnectionManager.url, cause: .unreachable(url: "ws://192.168.1.20:18789/ws")))
        await waitUntil { vm.endCause != nil && vm.warnings.isEmpty }
        #expect(gateway.warningReadCount >= 3) // on appear, then once per state change
        vm.onDisappear()
    }

    @Test func aDroppedSessionShowsItsTypedEndCauseAndRetryConnectsAgainWithItsToken() async {
        let endpoint = "ws://192.168.1.20:18789/ws"
        let gateway = ScriptedConnectionManager(ScriptedConnectionManager.connected(generation: 1))
        let models = FakeModelRepository(models: [])
        let vm = DashboardViewModel(gateway: gateway, models: models, ai: FakeAIRecommendationService())
        vm.onAppear()
        await vm.loadTask?.value
        await waitUntil { gateway.subscriberCount == 1 }
        #expect(vm.endCause == nil)

        let dropped = GatewayError.connectionClosed(url: endpoint, closeCode: 1008, reason: "device removed")
        gateway.emit(.error(url: ScriptedConnectionManager.url, cause: dropped))
        await waitUntil { vm.endCause != nil }

        #expect(vm.endCause?.hasPrefix("connectionClosed: ") == true)
        #expect(vm.endCause?.contains(endpoint) == true)
        #expect(vm.endCause?.contains("1008") == true)
        for feature in DashboardFeature.allCases {
            #expect(vm.unavailableReason(feature) == "Not connected to a gateway.")
        }

        await vm.retry()

        #expect(gateway.connectURLs == [ScriptedConnectionManager.url])
        #expect(gateway.connectTokens == ["shared-token"])
        await waitUntil { vm.connection.sessionGeneration == 2 }
        await vm.loadTask?.value
        #expect(vm.endCause == nil)
        #expect(models.getModelsCount == 2) // the new session reloaded the dashboard
        vm.onDisappear()
    }

    @Test func retryIsIgnoredUnlessTheSessionEnded() async {
        let gateway = ScriptedConnectionManager(ScriptedConnectionManager.connected(generation: 1))
        let vm = DashboardViewModel(gateway: gateway, models: FakeModelRepository(models: []), ai: FakeAIRecommendationService())

        await vm.retry()

        #expect(gateway.connectURLs.isEmpty)
    }

    // MARK: - Tiles gated on the session's capabilities

    private static func session(scopes: [String]) -> GatewayConnection {
        let hello = GatewayHello(
            serverVersion: "2026.10.3", methods: ["config.get", "config.patch", "models.list", "skills.status"],
            authMethod: "token", role: "operator", scopes: scopes, deviceToken: nil, gatewayAuthMode: "token",
            gatewayHost: nil
        )
        return ScriptedConnectionManager.connected(generation: 1, capabilities: hello.capabilities)
    }

    @Test func aSessionWithoutScopesDisablesConfigModelsAndSkillsWithAReason() {
        let gateway = ScriptedConnectionManager(Self.session(scopes: []))
        let vm = DashboardViewModel(gateway: gateway, models: FakeModelRepository(models: []), ai: FakeAIRecommendationService())

        #expect(vm.unavailableReason(.config)?.contains("operator.admin") == true)
        #expect(vm.unavailableReason(.models)?.contains("operator.read") == true)
        #expect(vm.unavailableReason(.skills)?.contains("operator.read") == true)
    }

    @Test func aSessionWithTheOperatorScopesEnablesConfigModelsAndSkills() {
        let gateway = ScriptedConnectionManager(Self.session(scopes: GatewayHandshake.operatorScopes))
        let vm = DashboardViewModel(gateway: gateway, models: FakeModelRepository(models: []), ai: FakeAIRecommendationService())

        for feature in DashboardFeature.allCases {
            #expect(vm.unavailableReason(feature) == nil)
        }
    }
}
