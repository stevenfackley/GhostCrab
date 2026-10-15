import Foundation
@testable import GhostCrab

// Hand-written fakes for the frozen `Domain/Repository` protocols.
//
// Each fake is a `final class` guarded by an `NSLock` and marked
// `@unchecked Sendable`. The protocols are `Sendable` and some requirements
// are synchronous (`GatewayConnectionManager.currentState`), which rules out
// actors. Behaviour is fixed at init and every call is recorded for
// assertions. Streams replay one snapshot and then finish, so `for await`
// loops in ViewModels end instead of leaking.
//
// Every test builds its own instances. Swift Testing runs suites in
// parallel, so nothing here is a shared global.

// MARK: - GatewayConnectionManager

final class FakeGatewayConnectionManager: GatewayConnectionManager, @unchecked Sendable {
    private let lock = NSLock()
    private let connectError: GatewayError?
    private let probeResult: Result<AuthRequirement, GatewayError>
    private let warnings: [ConnectionWarning]
    private var queuedFailures: [any Error]
    private var state: GatewayConnection = .disconnected
    private var connectLog: [(url: String, token: String?)] = []
    private var probeLog: [String] = []
    private var disconnects = 0

    /// - Parameters:
    ///   - connectError: When set, every `connect(url:token:)` throws it (after `failFirst` is used up).
    ///   - probeResult: What every `probeAuth(url:)` returns or throws.
    ///   - failFirst: Thrown by the first connects, one per call, in order (e.g. `PAIRING_REQUIRED` twice, or a
    ///     `CancellationError` for a connect a newer one superseded).
    ///   - warnings: What every `connectionWarnings()` reports.
    ///
    /// Like the real manager, a connect that throws a ``GatewayError`` leaves the state `.error(url, cause)`; one
    /// that throws anything else (a superseded connect) leaves the state alone.
    init(
        connectError: GatewayError? = nil,
        probeResult: Result<AuthRequirement, GatewayError> = .success(AuthRequirement.none),
        failFirst: [any Error] = [],
        warnings: [ConnectionWarning] = []
    ) {
        self.connectError = connectError
        self.probeResult = probeResult
        self.queuedFailures = failFirst
        self.warnings = warnings
    }

    var probedURLs: [String] { lock.withLock { probeLog } }
    var connectURLs: [String] { lock.withLock { connectLog.map { $0.url } } }
    var connectTokens: [String?] { lock.withLock { connectLog.map { $0.token } } }
    var disconnectCount: Int { lock.withLock { disconnects } }

    var currentState: GatewayConnection { lock.withLock { state } }

    func connectionStates() -> AsyncStream<GatewayConnection> {
        let snapshot = currentState
        return AsyncStream { continuation in
            continuation.yield(snapshot)
            continuation.finish()
        }
    }

    func probeAuth(url: String) async throws -> AuthRequirement {
        lock.withLock { probeLog.append(url) }
        return try probeResult.get()
    }

    func connect(url: String, token: String?) async throws {
        let failure = lock.withLock { () -> (any Error)? in
            connectLog.append((url: url, token: token))
            if queuedFailures.isEmpty { return connectError }
            return queuedFailures.removeFirst()
        }
        if let failure {
            if let gatewayError = failure as? GatewayError {
                lock.withLock { state = .error(url: url, cause: gatewayError) }
            }
            throw failure
        }
        lock.withLock {
            state = .connected(
                url: url,
                displayName: "Fake Gateway",
                version: "test",
                authRequirement: token == nil ? AuthRequirement.none : AuthRequirement.token,
                isHttps: url.lowercased().hasPrefix("https://"),
                capabilities: []
            )
        }
    }

    func disconnect() async {
        lock.withLock {
            disconnects += 1
            state = .disconnected
        }
    }

    func connectionWarnings() async -> [ConnectionWarning] {
        warnings
    }
}

// MARK: - ConnectionProfileRepository

final class FakeConnectionProfileRepository: ConnectionProfileRepository, @unchecked Sendable {
    private let lock = NSLock()
    private let deleteError: GatewayError?
    private let clearAllError: GatewayError?
    private var profiles: [ConnectionProfile]
    private var tokens: [String: String]
    private var saveLog: [(profile: ConnectionProfile, token: String?)] = []
    private var deleteLog: [String] = []
    private var clearAllCalls = 0
    private var tokenReads = 0

    /// - Parameters:
    ///   - deleteError: When set, every `deleteProfile(profileId:)` throws it and keeps the profile.
    ///   - clearAllError: When set, every `clearAll()` throws it and keeps everything.
    init(
        profiles: [ConnectionProfile] = [],
        tokens: [String: String] = [:],
        deleteError: GatewayError? = nil,
        clearAllError: GatewayError? = nil
    ) {
        self.profiles = profiles
        self.tokens = tokens
        self.deleteError = deleteError
        self.clearAllError = clearAllError
    }

    static func profile(url: String = "http://192.168.1.20:18789", hasToken: Bool = false) -> ConnectionProfile {
        ConnectionProfile(id: UUID(), displayName: "Basement", url: url, lastConnectedAt: nil, hasToken: hasToken)
    }

    var savedProfiles: [ConnectionProfile] { lock.withLock { saveLog.map { $0.profile } } }
    var savedTokens: [String?] { lock.withLock { saveLog.map { $0.token } } }
    var deleteAttempts: [String] { lock.withLock { deleteLog } }
    var clearAllCount: Int { lock.withLock { clearAllCalls } }
    var getTokenCount: Int { lock.withLock { tokenReads } }
    var storedProfileCount: Int { lock.withLock { profiles.count } }
    var storedTokenCount: Int { lock.withLock { tokens.count } }

    func observeProfiles() -> AsyncStream<[ConnectionProfile]> {
        let snapshot = lock.withLock { profiles }
        return AsyncStream { continuation in
            continuation.yield(snapshot)
            continuation.finish()
        }
    }

    func saveProfile(_ profile: ConnectionProfile, token: String?) async throws {
        lock.withLock {
            saveLog.append((profile: profile, token: token))
            profiles.removeAll { $0.id == profile.id }
            profiles.append(profile)
            tokens[profile.id.uuidString] = token
        }
    }

    func getToken(profileId: String) async throws -> String? {
        lock.withLock {
            tokenReads += 1
            return tokens[profileId]
        }
    }

    func deleteProfile(profileId: String) async throws {
        lock.withLock { deleteLog.append(profileId) }
        if let deleteError { throw deleteError }
        lock.withLock {
            profiles.removeAll { $0.id.uuidString == profileId }
            tokens[profileId] = nil
        }
    }

    func clearAll() async throws {
        lock.withLock { clearAllCalls += 1 }
        if let clearAllError { throw clearAllError }
        lock.withLock {
            profiles.removeAll()
            tokens.removeAll()
        }
    }
}

// MARK: - OnboardingRepository

final class FakeOnboardingRepository: OnboardingRepository, @unchecked Sendable {
    private let lock = NSLock()
    private var completed: Bool
    private var step: OnboardingStep
    private var stepLog: [OnboardingStep] = []
    private var markCompletedCalls = 0
    private var resetCalls = 0

    init(completed: Bool = false, savedStep: OnboardingStep = .welcome) {
        self.completed = completed
        self.step = savedStep
    }

    var savedSteps: [OnboardingStep] { lock.withLock { stepLog } }
    var markCompletedCount: Int { lock.withLock { markCompletedCalls } }
    var resetCount: Int { lock.withLock { resetCalls } }

    func isCompleted() async -> Bool { lock.withLock { completed } }

    func saveStep(_ step: OnboardingStep) async {
        lock.withLock {
            self.step = step
            stepLog.append(step)
        }
    }

    func getSavedStep() async -> OnboardingStep { lock.withLock { step } }

    func markCompleted() async {
        lock.withLock {
            completed = true
            markCompletedCalls += 1
        }
    }

    func reset() async {
        lock.withLock {
            completed = false
            step = .welcome
            resetCalls += 1
        }
    }
}

// MARK: - ModelRepository

final class FakeModelRepository: ModelRepository, @unchecked Sendable {
    private let lock = NSLock()
    private let setActiveError: GatewayError?
    private let getModelsError: GatewayError?
    private var models: [ModelInfo]
    private var setActiveLog: [String] = []
    private var getModelsCalls = 0
    private let unlistedPrimary: String?

    /// - Parameters:
    ///   - getModelsError: When set, every `getModels()` / `getModelCatalogue()` throws it.
    ///   - unlistedPrimary: What `getModelCatalogue()` reports as the unlisted primary.
    init(
        models: [ModelInfo],
        setActiveError: GatewayError? = nil,
        getModelsError: GatewayError? = nil,
        unlistedPrimary: String? = nil
    ) {
        self.models = models
        self.setActiveError = setActiveError
        self.getModelsError = getModelsError
        self.unlistedPrimary = unlistedPrimary
    }

    func getModelCatalogue() async throws -> ModelCatalogue {
        ModelCatalogue(models: try await getModels(), unlistedPrimary: unlistedPrimary)
    }

    static func model(_ id: String, active: Bool = false) -> ModelInfo {
        ModelInfo(id: id, provider: "openai", displayName: id.uppercased(), isActive: active, status: "ready", capabilities: [])
    }

    var setActiveCalls: [String] { lock.withLock { setActiveLog } }
    var getModelsCount: Int { lock.withLock { getModelsCalls } }

    func getModels() async throws -> [ModelInfo] {
        let snapshot: [ModelInfo] = lock.withLock {
            getModelsCalls += 1
            return models
        }
        if let getModelsError { throw getModelsError }
        return snapshot
    }

    func setActiveModel(modelId: String) async throws {
        lock.withLock { setActiveLog.append(modelId) }
        if let setActiveError { throw setActiveError }
        lock.withLock {
            models = models.map {
                ModelInfo(
                    id: $0.id,
                    provider: $0.provider,
                    displayName: $0.displayName,
                    isActive: $0.id == modelId,
                    status: $0.status,
                    capabilities: $0.capabilities
                )
            }
        }
    }
}

// MARK: - SettingsRepository

final class FakeSettingsRepository: SettingsRepository, @unchecked Sendable {
    private let lock = NSLock()
    private let writeDelays: [Duration]
    private var value: Bool
    private var writes: [Bool] = []
    private var writeCalls = 0

    /// - Parameter writeDelays: Delay before the nth write lands (call 0 uses
    ///   `writeDelays[0]`, and so on; missing entries mean no delay). Lets a
    ///   test make an earlier write finish after a later one.
    init(allowCleartext: Bool = false, writeDelays: [Duration] = []) {
        self.value = allowCleartext
        self.writeDelays = writeDelays
    }

    var writeLog: [Bool] { lock.withLock { writes } }

    func allowCleartextPublicIPs() -> AsyncStream<Bool> {
        let snapshot = lock.withLock { value }
        return AsyncStream { continuation in
            continuation.yield(snapshot)
            continuation.finish()
        }
    }

    func setAllowCleartextPublicIPs(_ enabled: Bool) async {
        let delay: Duration = lock.withLock {
            defer { writeCalls += 1 }
            return writeCalls < writeDelays.count ? writeDelays[writeCalls] : .zero
        }
        if delay > .zero {
            do {
                try await Task.sleep(for: delay)
            } catch {
                return  // cancelled: drop the write, like a cancelled real write
            }
        }
        lock.withLock {
            value = enabled
            writes.append(enabled)
        }
    }
}

// MARK: - ConfigRepository

final class FakeConfigRepository: ConfigRepository, @unchecked Sendable {
    private let lock = NSLock()
    private var config: OpenClawConfig
    private var updates: [String] = []

    init(config: OpenClawConfig) {
        self.config = config
    }

    var updatedSections: [String] { lock.withLock { updates } }

    func getConfig() async throws -> OpenClawConfig { lock.withLock { config } }

    func updateConfig(section: String, value: AnyCodable) async throws {
        lock.withLock {
            updates.append(section)
            var sections = config.sections
            sections[section] = value
            config = OpenClawConfig(sections: sections, etag: config.etag)
        }
    }
}

// MARK: - AIRecommendationService

final class FakeAIRecommendationService: AIRecommendationService, Sendable {
    private let available: Bool
    private let suggestedChanges: [SuggestedChange]

    init(available: Bool = false, suggestedChanges: [SuggestedChange] = []) {
        self.available = available
        self.suggestedChanges = suggestedChanges
    }

    func isAvailable() async -> Bool { available }

    func getRecommendation(query: String, context: RecommendationContext) async throws -> AIRecommendation {
        AIRecommendation(query: query, recommendation: "Use the fake model.", suggestedChanges: suggestedChanges)
    }
}

// MARK: - DiscoveryService

final class FakeDiscoveryService: DiscoveryService, @unchecked Sendable {
    private let lock = NSLock()
    private let gateways: [DiscoveredGateway]
    private var starts = 0
    private var stops = 0

    /// - Parameter gateways: Replayed, in order, by every `startDiscovery()` stream.
    init(gateways: [DiscoveredGateway] = []) {
        self.gateways = gateways
    }

    static func gateway(host: String = "192.168.1.20", port: Int = 18789) -> DiscoveredGateway {
        DiscoveredGateway(instanceName: "fake-\(host)", hostAddress: host, port: port, displayName: "Basement", version: "test")
    }

    var startCount: Int { lock.withLock { starts } }
    var stopCount: Int { lock.withLock { stops } }

    func startDiscovery() -> AsyncStream<DiscoveredGateway> {
        lock.withLock { starts += 1 }
        let snapshot = gateways
        return AsyncStream { continuation in
            for gateway in snapshot {
                continuation.yield(gateway)
            }
            continuation.finish()
        }
    }

    func stopDiscovery() async {
        lock.withLock { stops += 1 }
    }
}

// MARK: - InstalledSkillRepository

final class FakeInstalledSkillRepository: InstalledSkillRepository, @unchecked Sendable {
    private let lock = NSLock()
    private let installEvents: [SkillInstallProgress]
    private let uninstallError: SkillInstallError?
    private var skills: [InstalledSkill]
    private var installLog: [String] = []
    private var uninstallLog: [String] = []
    private var refreshCalls = 0
    private var enabledLog: [String] = []

    /// - Parameters:
    ///   - skills: Installed list replayed by `observeInstalled()` and returned by `refreshFromGateway()`.
    ///   - installEvents: Progress events every `install(slug:version:)` stream replays, in order.
    ///   - uninstallError: When set, `uninstall(slug:)` returns it as a failure and keeps the skill.
    init(
        skills: [InstalledSkill] = [],
        installEvents: [SkillInstallProgress] = [],
        uninstallError: SkillInstallError? = nil
    ) {
        self.skills = skills
        self.installEvents = installEvents
        self.uninstallError = uninstallError
    }

    static func skill(_ slug: String, version: String = "1.0.0") -> InstalledSkill {
        InstalledSkill(slug: slug, installedVersion: version, source: .clawHub, installedAt: Date(timeIntervalSince1970: 0))
    }

    var installedSlugs: [String] { lock.withLock { installLog } }
    var uninstalledSlugs: [String] { lock.withLock { uninstallLog } }
    var refreshCount: Int { lock.withLock { refreshCalls } }
    /// `"<skillKey>=<enabled>"` for every `setSkillEnabled` call, in order.
    var enabledChanges: [String] { lock.withLock { enabledLog } }

    func observeInstalled() -> AsyncStream<[InstalledSkill]> {
        let snapshot = lock.withLock { skills }
        return AsyncStream { continuation in
            continuation.yield(snapshot)
            continuation.finish()
        }
    }

    func refreshFromGateway() async -> Result<[InstalledSkill], any Error> {
        let snapshot: [InstalledSkill] = lock.withLock {
            refreshCalls += 1
            return skills
        }
        return .success(snapshot)
    }

    func install(slug: String, version: String?) -> AsyncStream<SkillInstallProgress> {
        lock.withLock { installLog.append(slug) }
        let events = installEvents
        return AsyncStream { continuation in
            for event in events {
                continuation.yield(event)
            }
            continuation.finish()
        }
    }

    func uninstall(slug: String) async -> Result<Void, any Error> {
        lock.withLock { uninstallLog.append(slug) }
        if let uninstallError { return .failure(uninstallError) }
        lock.withLock { skills.removeAll { $0.slug == slug } }
        return .success(())
    }

    func setSkillEnabled(skillKey: String, enabled: Bool) async -> Result<Void, any Error> {
        lock.withLock {
            enabledLog.append("\(skillKey)=\(enabled)")
            skills = skills.map {
                $0.slug == skillKey
                    ? InstalledSkill(
                        slug: $0.slug,
                        installedVersion: $0.installedVersion,
                        source: $0.source,
                        installedAt: $0.installedAt,
                        disabled: !enabled
                    )
                    : $0
            }
        }
        return .success(())
    }
}

// MARK: - ScopeProbe

final class FakeScopeProbe: ScopeProbe, @unchecked Sendable {
    private let lock = NSLock()
    private let result: ScopeProbeResult
    private var probes = 0

    init(result: ScopeProbeResult = .known(scopes: [])) {
        self.result = result
    }

    var probeCount: Int { lock.withLock { probes } }

    func probe() async -> ScopeProbeResult {
        lock.withLock { probes += 1 }
        return result
    }
}
