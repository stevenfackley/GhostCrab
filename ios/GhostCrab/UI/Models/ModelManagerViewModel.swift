import Foundation
import SwiftUI

/// ViewModel for the Model Manager screen.
///
/// Mirrors `ModelManagerViewModel.kt`. Activation has two steps, as the frozen
/// `ModelRepository.setActiveModel` contract requires ("Confirmation dialog is
/// the caller's responsibility"). A row tap stages ``pendingActivation`` (the
/// screen shows a confirmation dialog), and only ``confirmActivation(_:)``
/// talks to the gateway. A single tracked ``activationTask`` guarantees one
/// `setActiveModel` call per confirmation, however often the button is hit.
///
/// ``run()`` reloads the list for every new gateway session (the silent reconnect's new session included); the
/// reconnecting state itself neither reloads nor reports an error.
@MainActor
@Observable
public final class ModelManagerViewModel {

    // MARK: - Dependencies

    private let models: any ModelRepository
    /// Source of new sessions for ``run()``; `nil` loads once.
    private let gateway: (any GatewayConnectionManager)?

    // MARK: - Observable state

    /// Full list of models reported by the gateway.
    public private(set) var models_: [ModelInfo] = []

    /// The gateway's configured primary model, exactly as written, when no single listed model matches it (so none
    /// is active). Drives an amber banner; `nil` otherwise.
    public private(set) var unlistedPrimary: String?

    /// Identifier of the model currently being activated, or `nil` when idle.
    /// Drives an inline `ProgressView` next to the row.
    public private(set) var activatingId: String?

    /// True while the initial load is in flight (before any models have been shown).
    public private(set) var isLoading: Bool = false

    /// True when the gateway does not implement `models.list` (or a legacy
    /// REST gateway answered 404): this build of the gateway does not support
    /// the feature. Not an error.
    public private(set) var notSupported: Bool = false

    /// Last error surfaced to the user. Includes URL + status code + exception
    /// class when the source is a `GatewayError`. `nil` when no error pending.
    public var lastError: String?

    /// Model the user tapped and must confirm before activation. Drives the dialog.
    public private(set) var pendingActivation: ModelInfo?

    /// In-flight activation. Read-only outside the VM so tests can await it.
    @ObservationIgnored private(set) var activationTask: Task<Void, Never>?

    // MARK: - Init

    /// - Parameters:
    ///   - models: The model repository.
    ///   - gateway: Connection state source; a new session reloads the list (``run()``).
    public init(models: any ModelRepository, gateway: (any GatewayConnectionManager)? = nil) {
        self.models = models
        self.gateway = gateway
    }

    // MARK: - Lifecycle

    /// Loads the list, then reloads it for every new gateway session until the calling task is cancelled. Call from
    /// the screen's `.task` modifier.
    public func run() async {
        guard let gateway else {
            await load()
            return
        }
        var sessions = SessionTracker()
        sessions.markLoaded(gateway.currentState)
        await load()
        // The stream replays the current state first: a session that went live during the load reloads here.
        for await state in gateway.connectionStates() {
            if Task.isCancelled { return }
            if sessions.isNewSession(state) { await load() }
        }
    }

    /// Loads the model list. Call from `.refreshable` (``run()`` calls it on appear).
    public func load() async {
        isLoading = models_.isEmpty
        defer { isLoading = false }
        do {
            let catalogue = try await models.getModelCatalogue()
            self.models_ = catalogue.models
            self.unlistedPrimary = catalogue.unlistedPrimary
            self.notSupported = false
            self.lastError = nil
        } catch GatewayError.methodUnsupported {
            self.notSupported = true
            self.models_ = []
            self.unlistedPrimary = nil
            self.lastError = nil
        } catch GatewayError.api(_, let statusCode, _) where statusCode == 404 {
            self.notSupported = true
            self.models_ = []
            self.unlistedPrimary = nil
            self.lastError = nil
        } catch let error as GatewayError {
            self.lastError = describe(error)
        } catch {
            self.lastError = "\(type(of: error)): \(error.localizedDescription)"
        }
    }

    // MARK: - Activation (tap → confirm → activate)

    /// Row tap. Stages `id` for confirmation unless an activation is in flight
    /// or the model is already active.
    ///
    /// - Parameter id: The `ModelInfo.id` of the tapped row.
    public func requestActivation(_ id: String) {
        guard activationTask == nil, activatingId == nil else { return }
        guard let target = models_.first(where: { $0.id == id }), !target.isActive else { return }
        pendingActivation = target
    }

    /// Dialog cancel / dismiss. Never touches the gateway.
    public func cancelActivation() {
        pendingActivation = nil
    }

    /// Dialog confirm. Starts exactly one activation; repeat calls while it is
    /// in flight are ignored.
    ///
    /// Takes the model from the dialog's `presenting:` value rather than
    /// reading ``pendingActivation``, because SwiftUI may clear the dialog
    /// binding before it runs the button action.
    ///
    /// - Parameter model: The model shown in the dialog.
    public func confirmActivation(_ model: ModelInfo) {
        pendingActivation = nil
        guard activationTask == nil, activatingId == nil else { return }
        activationTask = Task {
            await self.setActive(model.id)
            self.activationTask = nil
        }
    }

    /// Sets the given model as active, then reloads so the checkmark follows
    /// the gateway's state. On failure the list is reloaded first and the
    /// error is set afterwards, because `load()` clears ``lastError`` on success.
    ///
    /// Private: the only way in is ``requestActivation(_:)`` then
    /// ``confirmActivation(_:)``, so no caller can skip the confirmation.
    ///
    /// - Parameter id: The `ModelInfo.id` of the model to activate.
    private func setActive(_ id: String) async {
        guard activatingId == nil else { return }
        guard let target = models_.first(where: { $0.id == id }) else { return }
        if target.isActive { return }

        activatingId = id
        defer { activatingId = nil }
        do {
            try await models.setActiveModel(modelId: id)
            await load()
        } catch let error as GatewayError {
            await load()
            self.lastError = "Failed to activate \(target.displayName) \u{2014} \(describe(error))"
        } catch {
            await load()
            self.lastError = "Failed to activate \(target.displayName) \u{2014} \(type(of: error)): \(error.localizedDescription)"
        }
    }

    // MARK: - Helpers

    private func describe(_ error: GatewayError) -> String {
        let typeName = String(describing: type(of: error))
        return "\(typeName): \(error.localizedDescription)"
    }
}
