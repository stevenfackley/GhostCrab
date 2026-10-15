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

    @ObservationIgnored
    private var connectionTask: Task<Void, Never>?

    /// In-flight config load. Read-only outside the VM so tests can await it.
    @ObservationIgnored private(set) var loadTask: Task<Void, Never>?

    /// In-flight section save. Read-only outside the VM so tests can await it.
    @ObservationIgnored private(set) var saveTask: Task<Void, Never>?

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

    /// Cancel background subscriptions and an in-flight load. Call from `.task` cancellation.
    /// An in-flight save is left to finish so the gateway write is not abandoned mid-way.
    public func stop() {
        connectionTask?.cancel()
        connectionTask = nil
        loadTask?.cancel()
        loadTask = nil
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
    /// Ignored while a load is already in flight.
    public func loadConfig() {
        guard loadTask == nil else { return }
        loadTask = Task { [weak self] in
            guard let self else { return }
            defer { if !Task.isCancelled { self.loadTask = nil } }
            self.state = .loading
            do {
                let config = try await self.configRepository.getConfig()
                if config.sections.isEmpty {
                    self.state = .noConfigApi
                } else {
                    self.state = .ready(config: config)
                }
            } catch {
                // Cancelled by stop() (the screen went away): whatever the
                // repository threw is about the abandoned request, not the
                // gateway, so publish no error.
                guard !Task.isCancelled else { return }
                if let e = error as? GatewayError {
                    self.state = .error(message: e.errorDescription ?? "Unknown error")
                } else {
                    self.state = .error(message: error.localizedDescription)
                }
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
    /// - On success: shows the re-read config with `saveSuccess = true`; drops `section`'s pending edit unless it was
    ///   re-edited during the save. Other sections' pending edits stay.
    /// - On HTTP 412 (stale `baseHash`): reloads config, keeps every pending edit (the conflicting one included,
    ///   so ``forceOverwrite(_:)`` can re-send it against the fresh hash) and surfaces a concurrent-edit warning.
    /// - On `configValidation`: sets the field error inline.
    /// - On any other error: transitions to ``ConfigEditorUiState/error(message:)``.
    ///
    /// Ignored while a save is already in flight.
    public func confirmSave(_ section: String) {
        guard saveTask == nil,
              case .ready(_, let pending, _, _, _, _) = state,
              let newValue = pending[section] else {
            return
        }
        // Clear the pending-save flag immediately so the sheet closes.
        cancelSave()

        saveTask = Task { [weak self] in
            guard let self else { return }
            defer { self.saveTask = nil }
            do {
                try await self.configRepository.updateConfig(section: section, value: newValue)
                let updated = try await self.configRepository.getConfig()
                self.applySaved(section: section, savedValue: newValue, updated: updated)
            } catch GatewayError.api(_, let statusCode, let body) where statusCode == 412 {
                // Concurrent edit. Reload (the repository now holds the fresh hash) + surface dialog. The latest
                // state's edits survive, including any made while the save was in flight.
                do {
                    let reloaded = try await self.configRepository.getConfig()
                    if case .ready(_, let pending, let fieldErrors, let saveSection, let saveSuccess, _) = self.state {
                        self.state = .ready(
                            config: reloaded,
                            pendingChanges: pending,
                            fieldErrors: fieldErrors,
                            pendingSaveSection: saveSection,
                            saveSuccess: saveSuccess,
                            concurrentEditSection: section
                        )
                    } else {
                        self.state = .ready(
                            config: reloaded,
                            pendingChanges: [section: newValue],
                            concurrentEditSection: section
                        )
                    }
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

    /// Publishes a successful save of `savedValue` to `section`, re-read as `updated` (port of Android's `afterSave`).
    /// The section's pending edit and field errors are dropped only while the edit still equals what was saved, so a
    /// re-edit made during the save survives; other sections' edits, the diff sheet and the conflict dialog are kept.
    /// No-op unless the editor is ``ConfigEditorUiState/ready(config:pendingChanges:fieldErrors:pendingSaveSection:saveSuccess:concurrentEditSection:)``
    /// (e.g. it went ``ConfigEditorUiState/disconnected`` during the save).
    private func applySaved(section: String, savedValue: AnyCodable, updated: OpenClawConfig) {
        guard case .ready(_, var pending, var fieldErrors, let saveSection, _, let concurrent) = state else {
            return
        }
        if pending[section] == savedValue {
            pending.removeValue(forKey: section)
            fieldErrors = fieldErrors.filter { !$0.key.hasPrefix("\(section).") }
        }
        state = .ready(
            config: updated,
            pendingChanges: pending,
            fieldErrors: fieldErrors,
            pendingSaveSection: saveSection,
            saveSuccess: true,
            concurrentEditSection: concurrent
        )
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

    /// The concurrent-edit dialog's "Reload and discard": the failed save already loaded the latest config, so this
    /// closes the dialog and drops `section`'s pending edit and field errors. Other sections' edits stay. Takes the
    /// section explicitly, so it works whether or not the alert binding cleared the dialog first.
    public func discardConflictingEdit(_ section: String) {
        acknowledgeConflict()
        discardSection(section)
    }

    /// The concurrent-edit dialog's "Force overwrite": closes the dialog and writes `section`'s pending edit again,
    /// against the hash of the config the conflict reloaded. No-op when the section has no pending edit.
    public func forceOverwrite(_ section: String) {
        acknowledgeConflict()
        confirmSave(section)
    }

    /// Dismisses the concurrent-edit warning dialog; pending edits stay.
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
