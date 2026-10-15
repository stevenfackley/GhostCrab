import Foundation
import Observation

/// ViewModel for the Config Editor screen.
///
/// Observes ``GatewayConnectionManager/connectionStates()`` to load config when
/// connected and transition to ``ConfigEditorUiState/disconnected`` when the
/// connection drops.
///
/// A silent reconnect after a dropped socket is not a new session: nothing reloads and nothing is reported. The
/// reconnected session (a new `sessionGeneration`) reloads the config. Unsaved edits survive reloads and failed saves:
/// while the editor is ready, a reload replaces only the config, and a failure is reported in ``saveError`` instead of
/// replacing the screen (port of Android's `saveError`).
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

    /// Why the last save (or a reload of the ready editor) failed, with the URL and the error; the pending edits are
    /// kept. Shown as an alert; ``clearSaveError()`` dismisses it. `nil` when there is nothing to report.
    public private(set) var saveError: String?

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
    /// the view so the subscription is bound to the view lifetime. Loads for the
    /// live session the stream replays, then once per new session; the silent
    /// reconnect's reconnecting state changes nothing.
    public func start() {
        guard connectionTask == nil else { return }
        connectionTask = Task { [weak self] in
            guard let self else { return }
            var sessions = SessionTracker()
            for await conn in self.connectionManager.connectionStates() {
                if Task.isCancelled { break }
                switch conn {
                case .connected:
                    if sessions.isNewSession(conn) { self.reloadForNewSession() }
                case .disconnected:
                    self.state = .disconnected
                case .connecting, .error:
                    break
                }
            }
        }
    }

    /// Replaces a load still running against an earlier session with one against the new session.
    private func reloadForNewSession() {
        loadTask?.cancel()
        loadTask = nil
        loadConfig()
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
    /// From any state but ``ConfigEditorUiState/ready(config:pendingChanges:fieldErrors:pendingSaveSection:saveSuccess:concurrentEditSection:)``:
    /// transitions through ``ConfigEditorUiState/loading`` then resolves to ready,
    /// ``ConfigEditorUiState/noConfigApi``, or ``ConfigEditorUiState/error(message:)``.
    /// A reload of a ready editor (a new session after a reconnect, or Retry) keeps it on screen with its unsaved
    /// edits, field errors and open dialogs: only the config is replaced, and a failed reload is reported in
    /// ``saveError`` instead of dropping them.
    /// Ignored while a load is already in flight.
    public func loadConfig() {
        guard loadTask == nil else { return }
        loadTask = Task { [weak self] in
            guard let self else { return }
            defer { if !Task.isCancelled { self.loadTask = nil } }
            if !self.isReady { self.state = .loading }
            do {
                let config = try await self.configRepository.getConfig()
                guard !Task.isCancelled else { return } // replaced by a newer load, or the screen went away
                self.applyLoaded(config)
            } catch {
                // Cancelled by stop() (the screen went away): whatever the
                // repository threw is about the abandoned request, not the
                // gateway, so publish no error.
                guard !Task.isCancelled else { return }
                let message = (error as? GatewayError)?.errorDescription ?? error.localizedDescription
                if self.isReady {
                    self.saveError = "Reload failed: \(message)"
                } else {
                    self.state = .error(message: message)
                }
            }
        }
    }

    /// Publishes a loaded `config`: a ready editor keeps its edits, field errors and dialogs.
    private func applyLoaded(_ config: OpenClawConfig) {
        if config.sections.isEmpty {
            state = .noConfigApi
        } else if case .ready(_, let pending, let fieldErrors, let saveSection, let saveSuccess, let concurrent) = state {
            state = .ready(
                config: config,
                pendingChanges: pending,
                fieldErrors: fieldErrors,
                pendingSaveSection: saveSection,
                saveSuccess: saveSuccess,
                concurrentEditSection: concurrent
            )
        } else {
            state = .ready(config: config)
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
    /// - On any other error (the session dropped mid-save included) or a failed reload after a 412: keeps the editor
    ///   ready with EVERY pending edit and reports the failure in ``saveError``. Only when the editor is no longer
    ///   ready does it transition to ``ConfigEditorUiState/error(message:)``.
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
                    let reason = (error as? GatewayError)?.errorDescription ?? body ?? "HTTP 412"
                    self.reportSaveFailure("Concurrent edit detected and reload failed: \(reason)")
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
                self.reportSaveFailure("Save failed: \(e.errorDescription ?? "unknown")")
            } catch {
                self.reportSaveFailure("Save failed: \(error.localizedDescription)")
            }
        }
    }

    /// A failed save keeps the ready editor and every pending edit (same shape as the conflict flow) and reports
    /// `message` in ``saveError``; an editor that is no longer ready shows it as ``ConfigEditorUiState/error(message:)``.
    private func reportSaveFailure(_ message: String) {
        if isReady {
            saveError = message
        } else {
            state = .error(message: message)
        }
    }

    private var isReady: Bool {
        if case .ready = state { return true }
        return false
    }

    /// Dismisses ``saveError``; the pending edits stay.
    public func clearSaveError() {
        saveError = nil
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
