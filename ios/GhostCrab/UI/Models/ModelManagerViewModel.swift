import Foundation
import SwiftUI

/// ViewModel for the Model Manager screen.
///
/// Mirrors `ModelManagerViewModel.kt` but simplified for the iOS port: the
/// activation flow does not gate behind a confirmation dialog (tap-to-activate
/// matches iOS idiom), and snackbar plumbing is replaced with inline error
/// surfacing.
@MainActor
@Observable
public final class ModelManagerViewModel {

    // MARK: - Dependencies

    private let models: any ModelRepository

    // MARK: - Observable state

    /// Full list of models reported by the gateway.
    public private(set) var models_: [ModelInfo] = []

    /// Identifier of the model currently being activated, or `nil` when idle.
    /// Drives an inline `ProgressView` next to the row.
    public private(set) var activatingId: String?

    /// True while the initial load is in flight (before any models have been shown).
    public private(set) var isLoading: Bool = false

    /// True when the gateway returned 404 on `/models` — the feature is not
    /// supported on this build of the gateway.
    public private(set) var notSupported: Bool = false

    /// Last error surfaced to the user. Includes URL + status code + exception
    /// class when the source is a `GatewayError`. `nil` when no error pending.
    public var lastError: String?

    // MARK: - Init

    public init(models: any ModelRepository) {
        self.models = models
    }

    // MARK: - Lifecycle

    /// Loads the model list. Call from the screen's `.task` modifier and from
    /// `.refreshable`.
    public func load() async {
        isLoading = models_.isEmpty
        defer { isLoading = false }
        do {
            let list = try await models.getModels()
            self.models_ = list
            self.notSupported = false
            self.lastError = nil
        } catch GatewayError.api(_, let statusCode, _) where statusCode == 404 {
            self.notSupported = true
            self.models_ = []
            self.lastError = nil
        } catch let error as GatewayError {
            self.lastError = describe(error)
        } catch {
            self.lastError = "\(type(of: error)): \(error.localizedDescription)"
        }
    }

    /// Sets the given model as active. Reloads the model list on success so the
    /// checkmark moves to the new row. On failure, surfaces an inline error and
    /// reloads the list (so the UI reflects the gateway's actual current state).
    ///
    /// - Parameter id: The `ModelInfo.id` of the model to activate.
    public func setActive(_ id: String) async {
        guard activatingId == nil else { return }
        guard let target = models_.first(where: { $0.id == id }) else { return }
        if target.isActive { return }

        activatingId = id
        defer { activatingId = nil }
        do {
            try await models.setActiveModel(modelId: id)
            await load()
        } catch let error as GatewayError {
            self.lastError = "Failed to activate \(target.displayName) \u{2014} \(describe(error))"
            // Best-effort reload so the list reflects gateway truth.
            await load()
        } catch {
            self.lastError = "Failed to activate \(target.displayName) \u{2014} \(type(of: error)): \(error.localizedDescription)"
            await load()
        }
    }

    // MARK: - Helpers

    private func describe(_ error: GatewayError) -> String {
        let typeName = String(describing: type(of: error))
        return "\(typeName): \(error.localizedDescription)"
    }
}
