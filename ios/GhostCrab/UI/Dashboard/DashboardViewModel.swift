import Foundation
import SwiftUI

/// Drives the connected-gateway dashboard.
///
/// Subscribes to `GatewayConnectionManager.connectionStates()` and tracks the
/// latest connection, the active model, and a list of recent telemetry events
/// (always empty in v1 — WebSocket streaming is out of scope per the project
/// charter).
///
/// Mirrors `DashboardViewModel.kt`, simplified for the iOS port: the periodic
/// `/health` polling and `Degraded` state are not part of v1 — the
/// `ConnectionStatusBar` at the top of the screen is the canonical liveness
/// indicator, and pull-to-refresh reloads the model list on demand.
@MainActor
@Observable
public final class DashboardViewModel {

    // MARK: - Dependencies

    private let gateway: any GatewayConnectionManager
    private let models: any ModelRepository
    private let ai: any AIRecommendationService

    // MARK: - Observable state

    /// Latest connection snapshot. Mirrors the Kotlin `state.connection` field.
    public private(set) var connection: GatewayConnection = .disconnected

    /// Currently active model, or `nil` if none / not yet loaded.
    public private(set) var activeModel: ModelInfo?

    /// Total number of models known to the gateway. Drives the "x models" subtitle.
    public private(set) var modelCount: Int = 0

    /// Whether the gateway exposes the AI recommendation skill — gates the AI tile.
    /// Resolved lazily on appear via `ai.isAvailable()`.
    public private(set) var aiAvailable: Bool = false

    /// Recent telemetry. Always empty in v1 — kept on the VM so the screen can
    /// render an empty section without conditional plumbing.
    public private(set) var recentTelemetry: [String] = []

    /// True while a refresh / initial load is in flight.
    public private(set) var isLoading: Bool = false

    /// Last error surfaced to the user (URL + status code + exception class
    /// when available). `nil` when no error is pending.
    public var lastError: String?

    /// `true` once the user asked to disconnect; repeat taps are ignored.
    public private(set) var isDisconnecting: Bool = false

    /// Problems that did not fail the live session (``GatewayConnectionManager/connectionWarnings()``), e.g.
    /// ``ConnectionWarning/deviceTokenNotSaved`` after an approval: the session works, but later connects need the
    /// gateway token. Shown amber; empty when there is nothing to report.
    public private(set) var warnings: [ConnectionWarning] = []

    // MARK: - Internal tasks

    @ObservationIgnored
    private var connectionObservationTask: Task<Void, Never>?

    /// In-flight model load from `onAppear`. Read-only outside the VM so tests can await it.
    @ObservationIgnored private(set) var loadTask: Task<Void, Never>?

    /// In-flight AI availability probe. Read-only outside the VM so tests can await it.
    @ObservationIgnored private(set) var aiTask: Task<Void, Never>?

    // MARK: - Init

    public init(
        gateway: any GatewayConnectionManager,
        models: any ModelRepository,
        ai: any AIRecommendationService
    ) {
        self.gateway = gateway
        self.models = models
        self.ai = ai
        self.connection = gateway.currentState
    }

    deinit {
        connectionObservationTask?.cancel()
    }

    // MARK: - Lifecycle

    /// Call from the screen's `.task` modifier. Starts observing the connection
    /// stream, loads the active model, and probes AI availability. Loads that
    /// are already running are not started again.
    public func onAppear() {
        startObservingConnection()
        if loadTask == nil {
            loadTask = Task {
                await self.loadWarnings()
                await self.loadActiveModel()
                if !Task.isCancelled { self.loadTask = nil }
            }
        }
        if aiTask == nil {
            aiTask = Task {
                await self.probeAIAvailability()
                if !Task.isCancelled { self.aiTask = nil }
            }
        }
    }

    /// Call from the screen's `.onDisappear` (e.g. pushing Models / Config).
    /// Cancels in-flight work; `onAppear()` restarts it on return.
    public func onDisappear() {
        connectionObservationTask?.cancel()
        connectionObservationTask = nil
        loadTask?.cancel()
        loadTask = nil
        aiTask?.cancel()
        aiTask = nil
    }

    /// Manual refresh — invoked by `.refreshable`. Reloads the model list.
    public func refresh() async {
        await loadWarnings()
        await loadActiveModel()
    }

    /// Disconnects from the gateway once. The caller pops the nav stack only
    /// when this returns `true`, so a double tap cannot dismiss twice.
    ///
    /// - Returns: `true` for the call that disconnected; `false` for repeats.
    public func disconnect() async -> Bool {
        guard !isDisconnecting else { return false }
        isDisconnecting = true
        await gateway.disconnect()
        return true
    }

    // MARK: - Connection observation

    private func startObservingConnection() {
        connectionObservationTask?.cancel()
        let stream = gateway.connectionStates()
        // Task inherits @MainActor from the enclosing class — direct property
        // writes inside `for await` are MainActor-isolated by construction.
        connectionObservationTask = Task { [weak self] in
            for await state in stream {
                guard let self else { return }
                self.connection = state
                if case .disconnected = state {
                    return
                }
            }
        }
    }

    // MARK: - Data loaders

    private func loadActiveModel() async {
        isLoading = true
        defer { isLoading = false }
        do {
            let list = try await models.getModels()
            guard !Task.isCancelled else { return }
            self.modelCount = list.count
            self.activeModel = list.first(where: { $0.isActive })
            self.lastError = nil
        } catch {
            // A cancelled load (left the screen) is not an error worth a banner:
            // URLSession reports cancellation as `URLError.cancelled`, which the
            // client maps to `.unreachable`.
            guard !Task.isCancelled else { return }
            if let gatewayError = error as? GatewayError {
                self.lastError = describe(error: gatewayError)
            } else {
                self.lastError = "\(type(of: error)): \(error.localizedDescription)"
            }
        }
    }

    private func loadWarnings() async {
        let current = await gateway.connectionWarnings()
        guard !Task.isCancelled else { return }
        self.warnings = current
    }

    private func probeAIAvailability() async {
        let available = await ai.isAvailable()
        guard !Task.isCancelled else { return }
        self.aiAvailable = available
    }

    // MARK: - Helpers

    /// Format an error into the explicit URL + status + exception-class form
    /// the brand guide requires. Falls back to the localized description when
    /// the structured form isn't available.
    private func describe(error: GatewayError) -> String {
        let typeName = String(describing: type(of: error))
        return "\(typeName): \(error.localizedDescription)"
    }
}
