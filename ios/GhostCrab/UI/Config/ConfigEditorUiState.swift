import Foundation

/// UI state for the Config Editor screen.
///
/// Direct port of `ConfigEditorUiState.kt`. The gateway is always the source
/// of truth — there is no optimistic local update.
public enum ConfigEditorUiState: Sendable {

    /// Initial state while the config is being fetched from the gateway.
    case loading

    /// No active gateway connection. Screen should navigate back.
    case disconnected

    /// The gateway responded but exposed no editable config sections — typically
    /// the upstream `ghcr.io/openclaw/openclaw` image which serves an HTML admin
    /// UI at `/config` instead of JSON. The connection is healthy; the config API
    /// is just absent on this gateway version.
    case noConfigApi

    /// A non-recoverable error occurred (other than concurrency conflict).
    ///
    /// - Parameter message: Exact error text including URL, status code, and
    ///   exception class where applicable.
    case error(message: String)

    /// Config loaded and ready for display / editing.
    ///
    /// - Parameters:
    ///   - config: Authoritative config as returned by the gateway.
    ///   - pendingChanges: Section key → full edited `AnyCodable`. Only sections
    ///     with unsaved edits are present.
    ///   - fieldErrors: Field path → error message
    ///     (e.g. `"gateway.http.port"` → `"Must be 1–65535"`).
    ///   - pendingSaveSection: When non-nil, the diff sheet is shown for this
    ///     section key.
    ///   - saveSuccess: When `true`, show a success snackbar/banner; call
    ///     `ConfigEditorViewModel.clearSaveSuccess()` to dismiss.
    ///   - concurrentEditSection: When non-nil, show a concurrent-edit warning
    ///     dialog for this section.
    case ready(
        config: OpenClawConfig,
        pendingChanges: [String: AnyCodable] = [:],
        fieldErrors: [String: String] = [:],
        pendingSaveSection: String? = nil,
        saveSuccess: Bool = false,
        concurrentEditSection: String? = nil
    )
}
