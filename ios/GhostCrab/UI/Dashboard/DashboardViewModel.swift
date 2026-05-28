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

    // MARK: - Internal observation tasks

    private var connectionObservationTask: Task<Void, Never>?

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
    /// stream, loads the active model, and probes AI availability.
    public func onAppear() {
        startObservingConnection()
        Task { await loadActiveModel() }
        Task { await probeAIAvailability() }
    }

    /// Manual refresh — invoked by `.refreshable`. Reloads the model list.
    public func refresh() async {
        await loadActiveModel()
    }

    /// Disconnects from the gateway. Caller is responsible for popping the
    /// nav stack after this completes (the screen pops via `dismiss()`).
    public func disconnect() async {
        await gateway.disconnect()
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
            self.modelCount = list.count
            self.activeModel = list.first(where: { $0.isActive })
            self.lastError = nil
        } catch let error as GatewayError {
            self.lastError = describe(error: error)
        } catch {
            self.lastError = "\(type(of: error)): \(error.localizedDescription)"
        }
    }

    private func probeAIAvailability() async {
        let available = await ai.isAvailable()
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
