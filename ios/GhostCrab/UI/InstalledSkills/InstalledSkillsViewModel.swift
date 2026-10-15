import Foundation
import Observation

/// ViewModel for the Installed Skills screen.
///
/// Subscribes to ``InstalledSkillRepository/observeInstalled()`` on ``start()``
/// for live list updates, and spawns one `Task` per ongoing install to fan in
/// each install's progress stream into a per-slug `progress` map. Removal
/// (which disables the skill, `skills.update`) tracks its pending, confirm and
/// in-flight state locally: the call reports no progress.
///
/// Every new gateway session (the silent reconnect's new session included) refreshes the list; the reconnecting state
/// itself neither refreshes nor reports an error.
@MainActor
@Observable
public final class InstalledSkillsViewModel {

    // MARK: - Public state

    /// Live list mirrored from the repository.
    public private(set) var skills: [InstalledSkill] = []

    /// Per-slug in-flight install progress. Cleared on terminal `.succeeded` /
    /// `.failed` event after a short delay so the row briefly shows the final
    /// state.
    public private(set) var installProgress: [String: SkillInstallProgress] = [:]

    /// Per-slug terminal install error, kept around for the inline retry row.
    public private(set) var installErrors: [String: SkillInstallError] = [:]

    /// Slug currently awaiting uninstall confirmation; `nil` = no dialog.
    public var pendingUninstallSlug: String?

    /// Slug for which an uninstall RPC is in flight.
    public private(set) var uninstallingSlug: String?

    /// Transient error message for failed refresh / uninstall.
    public var lastError: String?

    /// True while the initial / pull-to-refresh fetch is in flight.
    public private(set) var isRefreshing: Bool = false

    /// True until the first refresh resolves (success or empty).
    public private(set) var isFirstLoad: Bool = true

    // MARK: - Dependencies

    private let repo: any InstalledSkillRepository
    /// Source of new sessions; `nil` refreshes only on ``start()`` and pull-to-refresh.
    private let gateway: (any GatewayConnectionManager)?

    // MARK: - Background tasks

    @ObservationIgnored
    private var observeTask: Task<Void, Never>?
    @ObservationIgnored
    private var installTasks: [String: Task<Void, Never>] = [:]
    /// Refreshes on every new session. Read-only outside the VM so tests can cancel and await it.
    @ObservationIgnored private(set) var sessionTask: Task<Void, Never>?

    // MARK: - Init / lifecycle

    /// - Parameters:
    ///   - skills: The installed-skill repository.
    ///   - gateway: Connection state source; a new session refreshes the list.
    public init(skills: any InstalledSkillRepository, gateway: (any GatewayConnectionManager)? = nil) {
        self.repo = skills
        self.gateway = gateway
    }

    /// Begin observing the live list and trigger an initial refresh. Call from
    /// a `.task` modifier on the screen.
    public func start() {
        guard observeTask == nil else { return }
        observeTask = Task { [weak self, repo] in
            for await list in repo.observeInstalled() {
                if Task.isCancelled { break }
                guard let self else { break }
                self.skills = list
            }
        }
        Task { await refresh() }
        if let gateway {
            let loadedFor = gateway.currentState
            sessionTask = Task { [weak self] in
                var sessions = SessionTracker()
                sessions.markLoaded(loadedFor)
                for await state in gateway.connectionStates() {
                    if Task.isCancelled { break }
                    guard let self else { break }
                    if sessions.isNewSession(state) { await self.refresh() }
                }
            }
        }
    }

    /// Cancel observation and all in-flight install tasks. Call from `.task`
    /// cancellation.
    public func stop() {
        observeTask?.cancel()
        observeTask = nil
        sessionTask?.cancel()
        sessionTask = nil
        for task in installTasks.values { task.cancel() }
        installTasks.removeAll()
    }

    deinit {
        observeTask?.cancel()
        sessionTask?.cancel()
        for task in installTasks.values { task.cancel() }
    }

    // MARK: - Row formatting

    /// The row's version label, `v<version>`; `nil` when the gateway reported no version (a `skills.status` entry
    /// without `clawhub.installedVersion`), so the row shows only the source instead of a bare `v`.
    nonisolated static func versionLabel(_ version: String) -> String? {
        let trimmed = version.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : "v" + trimmed
    }

    // MARK: - Public API

    /// Triggers a one-shot refresh from `skills.status`. Sets ``lastError`` on
    /// failure; leaves the previous list intact.
    public func refresh() async {
        isRefreshing = true
        defer {
            isRefreshing = false
            isFirstLoad = false
        }
        let result = await repo.refreshFromGateway()
        switch result {
        case .success:
            break
        case .failure(let error):
            lastError = (error as? GatewayError)?.errorDescription ?? error.localizedDescription
        }
    }

    /// Kick off an install. Subscribes to the per-install progress stream in a
    /// dedicated `Task` (one per slug). Calling again for the same slug while
    /// an install is already in-flight is a no-op.
    public func install(slug: String, version: String?) {
        guard installTasks[slug] == nil else { return }
        // Clear any prior terminal error for this slug.
        installErrors.removeValue(forKey: slug)
        installProgress[slug] = .connecting(target: slug)

        let task = Task { [weak self, repo] in
            for await progress in repo.install(slug: slug, version: version) {
                if Task.isCancelled { break }
                guard let self else { break }
                self.installProgress[slug] = progress
                switch progress {
                case .succeeded:
                    // Brief lingering UI feedback, then clear.
                    try? await Task.sleep(nanoseconds: 800_000_000)
                    self.installProgress.removeValue(forKey: slug)
                case .failed(let error):
                    self.installErrors[slug] = error
                    self.installProgress.removeValue(forKey: slug)
                case .idle, .connecting, .downloading, .verifying, .applying:
                    break
                }
            }
            self?.installTasks.removeValue(forKey: slug)
        }
        installTasks[slug] = task
    }

    /// Retry a previously-failed install — only meaningful if the last error
    /// for `slug` reported ``SkillInstallError/isRetryable`` `== true`.
    public func retryInstall(slug: String, version: String? = nil) {
        installErrors.removeValue(forKey: slug)
        install(slug: slug, version: version)
    }

    /// Dismiss the inline install error row for a slug without retrying.
    public func dismissInstallError(slug: String) {
        installErrors.removeValue(forKey: slug)
    }

    /// Opens the uninstall confirmation dialog for `slug`.
    public func requestUninstall(_ slug: String) {
        pendingUninstallSlug = slug
    }

    /// Dismiss the uninstall confirmation dialog without acting.
    public func cancelUninstall() {
        pendingUninstallSlug = nil
    }

    /// Confirm removal of the currently-pending slug, which on OpenClaw means
    /// disabling it (`skills.update`, `enabled = false`; there is no removal
    /// RPC). Surfaces errors via ``lastError``.
    public func confirmUninstall() {
        guard let slug = pendingUninstallSlug else { return }
        pendingUninstallSlug = nil
        uninstallingSlug = slug

        Task { [weak self, repo] in
            // OpenClaw has no skill removal: disable it (skills.update, enabled = false). ADR-0007.
            let result = await repo.setSkillEnabled(skillKey: slug, enabled: false)
            guard let self else { return }
            self.uninstallingSlug = nil
            if case .failure(let error) = result {
                self.lastError = (error as? GatewayError)?.errorDescription ?? error.localizedDescription
            }
        }
    }

    /// Convenience direct call from a row's Menu.
    public func uninstall(slug: String) {
        requestUninstall(slug)
    }
}
