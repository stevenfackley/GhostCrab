import Foundation
import Observation

/// ViewModel for the Config Editor screen.
///
/// Observes ``GatewayConnectionManager/connectionStates()`` to load config when
/// connected and transition to ``ConfigEditorUiState/disconnected`` when the
/// connection drops.
///
/// All mutations are server-confirmed — there is no optimistic local state.
///
/// Direct port of `ConfigEditorViewModel.kt`.
@MainActor
@Observable
public final class ConfigEditorViewModel {

    // MARK: - Public state

    /// Current UI state. Drives the screen `switch`.
    public private(set) var state: ConfigEditorUiState = .loading

    // MARK: - Dependencies

    private let configRepository: any ConfigRepository
    private let connectionManager: any GatewayConnectionManager

    // MARK: - Background tasks

    private var connectionTask: Task<Void, Never>?

    // MARK: - Init / lifecycle

    /// - Parameters:
    ///   - config: Frozen v1 config repository.
    ///   - gateway: Connection lifecycle source — used to auto-load on connect
    ///     and reset to ``ConfigEditorUiState/disconnected`` on drop.
    public init(
        config: any ConfigRepository,
        gateway: any GatewayConnectionManager
    ) {
        self.configRepository = config
        self.connectionManager = gateway
    }

    /// Begin observing the connection stream. Call from a `.task` modifier on
    /// the view so the subscription is bound to the view lifetime.
    public func start() {
        guard connectionTask == nil else { return }
        connectionTask = Task { [weak self] in
            guard let self else { return }
            for await conn in self.connectionManager.connectionStates() {
                if Task.isCancelled { break }
                switch conn {
                case .connected:
                    self.loadConfig()
                case .disconnected:
                    self.state = .disconnected
                case .connecting, .error:
                    break
                }
            }
        }
    }

    /// Cancel background subscriptions. Call from `.task` cancellation.
    public func stop() {
        connectionTask?.cancel()
        connectionTask = nil
    }

    deinit {
        connectionTask?.cancel()
    }

    // MARK: - Public API

    /// Fetches (or re-fetches) the config from the gateway.
    ///
    /// Transitions through ``ConfigEditorUiState/loading`` then resolves to
    /// either ``ConfigEditorUiState/ready(config:pendingChanges:fieldErrors:pendingSaveSection:saveSuccess:concurrentEditSection:)``,
    /// ``ConfigEditorUiState/noConfigApi``, or ``ConfigEditorUiState/error(message:)``.
    public func loadConfig() {
        Task { [weak self] in
            guard let self else { return }
            self.state = .loading
            do {
                let config = try await self.configRepository.getConfig()
                if config.sections.isEmpty {
                    self.state = .noConfigApi
                } else {
                    self.state = .ready(config: config)
                }
            } catch let e as GatewayError {
                self.state = .error(message: e.errorDescription ?? "Unknown error")
            } catch {
                self.state = .error(message: error.localizedDescription)
            }
        }
    }

    /// Records a user edit to a section. Does not write to the gateway.
    ///
    /// - Parameters:
    ///   - section: Top-level section key.
    ///   - value: Full edited section as an ``AnyCodable``.
    public func editSection(_ section: String, value: AnyCodable) {
        guard case .ready(let config, var pending, var fieldErrors, let saveSection, let saveSuccess, let concurrent) = state else {
            return
        }
        pending[section] = value
        // Clear any field errors scoped under this section.
        fieldErrors = fieldErrors.filter { !$0.key.hasPrefix("\(section).") }
        state = .ready(
            config: config,
            pendingChanges: pending,
            fieldErrors: fieldErrors,
            pendingSaveSection: saveSection,
            saveSuccess: saveSuccess,
            concurrentEditSection: concurrent
        )
    }

    /// Records or clears a client-side validation error for a specific field path.
    ///
    /// - Parameters:
    ///   - fieldPath: Dot-separated path, e.g. `"gateway.http.port"`.
    ///   - error: Human-readable error message, or `nil` to clear.
    public func setFieldError(_ fieldPath: String, error: String?) {
        guard case .ready(let config, let pending, var fieldErrors, let saveSection, let saveSuccess, let concurrent) = state else {
            return
        }
        if let error {
            fieldErrors[fieldPath] = error
        } else {
            fieldErrors.removeValue(forKey: fieldPath)
        }
        state = .ready(
            config: config,
            pendingChanges: pending,
            fieldErrors: fieldErrors,
            pendingSaveSection: saveSection,
            saveSuccess: saveSuccess,
            concurrentEditSection: concurrent
        )
    }

    /// Opens the diff confirmation sheet for a section that has pending changes.
    ///
    /// No-op if the section has no pending changes.
    public func requestSave(_ section: String) {
        guard case .ready(let config, let pending, let fieldErrors, _, let saveSuccess, let concurrent) = state,
              pending[section] != nil else {
            return
        }
        state = .ready(
            config: config,
            pendingChanges: pending,
            fieldErrors: fieldErrors,
            pendingSaveSection: section,
            saveSuccess: saveSuccess,
            concurrentEditSection: concurrent
        )
    }

    /// Dismisses the diff sheet without writing to the gateway.
    public func cancelSave() {
        guard case .ready(let config, let pending, let fieldErrors, _, let saveSuccess, let concurrent) = state else {
            return
        }
        state = .ready(
            config: config,
            pendingChanges: pending,
            fieldErrors: fieldErrors,
            pendingSaveSection: nil,
            saveSuccess: saveSuccess,
            concurrentEditSection: concurrent
        )
    }

    /// Writes the pending change for `section` to the gateway, then re-reads
    /// config.
    ///
    /// - On success: transitions to a fresh ready state with `saveSuccess = true`.
    /// - On HTTP 412: reloads config and surfaces a concurrent-edit warning.
    /// - On `configValidation`: sets the field error inline.
    /// - On any other error: transitions to ``ConfigEditorUiState/error(message:)``.
    public func confirmSave(_ section: String) {
        guard case .ready(_, let pending, _, _, _, _) = state,
              let newValue = pending[section] else {
            return
        }
        // Clear the pending-save flag immediately so the sheet closes.
        cancelSave()

        Task { [weak self] in
            guard let self else { return }
            do {
                try await self.configRepository.updateConfig(section: section, value: newValue)
                let updated = try await self.configRepository.getConfig()
                self.state = .ready(config: updated, saveSuccess: true)
            } catch GatewayError.api(_, let statusCode, let body) where statusCode == 412 {
                // Concurrent edit. Reload + surface dialog.
                do {
                    let reloaded = try await self.configRepository.getConfig()
                    self.state = .ready(
                        config: reloaded,
                        concurrentEditSection: section
                    )
                } catch {
                    self.state = .error(
                        message: "Concurrent edit detected and reload failed: \(body ?? "HTTP 412")"
                    )
                }
            } catch GatewayError.configValidation(let field, let reason) {
                // Inline field error if still ready, otherwise surface as full error.
                if case .ready(let config, let pending, var fieldErrors, let saveSection, let saveSuccess, let concurrent) = self.state {
                    fieldErrors[field] = reason
                    self.state = .ready(
                        config: config,
                        pendingChanges: pending,
                        fieldErrors: fieldErrors,
                        pendingSaveSection: saveSection,
                        saveSuccess: saveSuccess,
                        concurrentEditSection: concurrent
                    )
                } else {
                    self.state = .error(message: "Validation error: \(field): \(reason)")
                }
            } catch let e as GatewayError {
                self.state = .error(message: "Save failed: \(e.errorDescription ?? "unknown")")
            } catch {
                self.state = .error(message: "Save failed: \(error.localizedDescription)")
            }
        }
    }

    /// Discards all pending edits for a section, including associated field
    /// errors.
    public func discardSection(_ section: String) {
        guard case .ready(let config, var pending, var fieldErrors, let saveSection, let saveSuccess, let concurrent) = state else {
            return
        }
        pending.removeValue(forKey: section)
        fieldErrors = fieldErrors.filter { !$0.key.hasPrefix("\(section).") }
        state = .ready(
            config: config,
            pendingChanges: pending,
            fieldErrors: fieldErrors,
            pendingSaveSection: saveSection,
            saveSuccess: saveSuccess,
            concurrentEditSection: concurrent
        )
    }

    /// Discards every pending edit across all sections.
    public func discardChanges() {
        guard case .ready(let config, _, _, let saveSection, let saveSuccess, let concurrent) = state else {
            return
        }
        state = .ready(
            config: config,
            pendingChanges: [:],
            fieldErrors: [:],
            pendingSaveSection: saveSection,
            saveSuccess: saveSuccess,
            concurrentEditSection: concurrent
        )
    }

    /// Clears the save-success flag after the snackbar/banner has been shown.
    public func clearSaveSuccess() {
        guard case .ready(let config, let pending, let fieldErrors, let saveSection, _, let concurrent) = state else {
            return
        }
        state = .ready(
            config: config,
            pendingChanges: pending,
            fieldErrors: fieldErrors,
            pendingSaveSection: saveSection,
            saveSuccess: false,
            concurrentEditSection: concurrent
        )
    }

    /// Dismisses the concurrent-edit warning dialog.
    public func acknowledgeConflict() {
        guard case .ready(let config, let pending, let fieldErrors, let saveSection, let saveSuccess, _) = state else {
            return
        }
        state = .ready(
            config: config,
            pendingChanges: pending,
            fieldErrors: fieldErrors,
            pendingSaveSection: saveSection,
            saveSuccess: saveSuccess,
            concurrentEditSection: nil
        )
    }
}
