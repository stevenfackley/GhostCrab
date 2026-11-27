import Foundation
import Observation

/// What the Installed Skills screen shows: the same states as Android's `InstalledSkillsUiState`.
public enum InstalledSkillsPhase: Equatable, Sendable {
    /// Nothing to show yet, and something is in flight that ends: the live session's first `skills.status` read, or
    /// the connect / silent reconnect it waits for (that one ends in a new session, which reads, or in `.error`).
    case loading
    /// The live session's list is shown (``InstalledSkillsViewModel/skills``); an empty one is "No skills installed".
    case ready
    /// The live session's first read failed, so there is no list to show (not even an empty one).
    case loadFailed(message: String)
    /// No live connection: never connected, disconnected, or the connect or silent reconnect failed. No rows are
    /// shown: they belong to a session that is gone. `reason` is the failure in plain words, `nil` when the
    /// connection did not end in one.
    case disconnected(reason: String?)
}

/// ViewModel for the Installed Skills screen.
///
/// Subscribes to ``InstalledSkillRepository/observeInstalled()`` on ``start()``
/// for live list updates, and spawns one `Task` per ongoing install to fan in
/// each install's progress stream into a per-slug `progress` map. Removal
/// (which disables the skill, `skills.update`) tracks its pending, confirm and
/// in-flight state locally: the call reports no progress.
///
/// The connection state drives ``phase``; Android's `InstalledSkillsViewModel` follows the same rules:
/// - A live session (`.connected`, not reconnecting) is read with `skills.status`: `.loading` until it answers, then
///   `.ready` (an empty list is "No skills installed") or `.loadFailed`. Every new session (the silent reconnect's
///   included) is read again.
/// - The silent reconnect keeps the rows on screen; with nothing loaded yet it shows `.loading`, which ends when the
///   reconnect does. It neither reads nor reports an error.
/// - `.error` (the connect or the silent reconnect failed) and `.disconnected` show `.disconnected`, without rows;
///   `.connecting` shows `.loading`.
/// - Lists from the repository and read results count only while their session is live.
///
/// Without a gateway (tests) every read counts and nothing else drives the phase.
@MainActor
@Observable
public final class InstalledSkillsViewModel {

    // MARK: - Public state

    /// What the screen shows besides the rows. See ``InstalledSkillsPhase``.
    public private(set) var phase: InstalledSkillsPhase = .loading

    /// The live session's list; empty outside a session.
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

    /// Transient error message for a failed refresh while rows are shown, or a failed uninstall.
    public var lastError: String?

    /// True while a `skills.status` read (the first one or a pull-to-refresh) is in flight.
    public private(set) var isRefreshing: Bool = false

    // MARK: - Dependencies

    private let repo: any InstalledSkillRepository
    /// Source of the connection state; `nil` reads only on ``start()`` and pull-to-refresh.
    private let gateway: (any GatewayConnectionManager)?

    // MARK: - Background tasks

    @ObservationIgnored
    private var observeTask: Task<Void, Never>?
    @ObservationIgnored
    private var installTasks: [String: Task<Void, Never>] = [:]
    /// Follows the connection state. Read-only outside the VM so tests can cancel and await it.
    @ObservationIgnored private(set) var sessionTask: Task<Void, Never>?
    /// The `skills.status` read in flight. Read-only outside the VM so tests can await it.
    @ObservationIgnored private(set) var loadTask: Task<Void, Never>?

    // MARK: - Connection bookkeeping

    /// The latest connection state; `nil` without a gateway.
    @ObservationIgnored private var connection: GatewayConnection?
    /// The session last read (or being read).
    @ObservationIgnored private var sessions = SessionTracker()

    // MARK: - Init / lifecycle

    /// - Parameters:
    ///   - skills: The installed-skill repository.
    ///   - gateway: Connection state source; it drives ``phase``, and a new session reads the list.
    public init(skills: any InstalledSkillRepository, gateway: (any GatewayConnectionManager)? = nil) {
        self.repo = skills
        self.gateway = gateway
        self.connection = gateway?.currentState
    }

    /// Begin observing the live list and the connection state, and read the live session's list. Call from a `.task`
    /// modifier on the screen.
    public func start() {
        guard observeTask == nil else { return }
        observeTask = Task { [weak self, repo] in
            for await list in repo.observeInstalled() {
                if Task.isCancelled { break }
                guard let self else { break }
                self.apply(observed: list)
            }
        }
        guard let gateway else {
            Task { await refresh() }
            return
        }
        // At once, so the first frame already shows the right state; the stream below replays it, which is a no-op.
        onConnection(gateway.currentState)
        sessionTask = Task { [weak self] in
            for await state in gateway.connectionStates() {
                if Task.isCancelled { break }
                guard let self else { break }
                self.onConnection(state)
            }
        }
    }

    /// Cancel observation, the read in flight and all in-flight install tasks. Call from `.task` cancellation. The
    /// next ``start()`` reads the live session again.
    public func stop() {
        observeTask?.cancel()
        observeTask = nil
        sessionTask?.cancel()
        sessionTask = nil
        loadTask?.cancel()
        loadTask = nil
        isRefreshing = false
        sessions = SessionTracker()
        for task in installTasks.values { task.cancel() }
        installTasks.removeAll()
    }

    deinit {
        observeTask?.cancel()
        sessionTask?.cancel()
        loadTask?.cancel()
        for task in installTasks.values { task.cancel() }
    }

    // MARK: - Connection state

    private func onConnection(_ state: GatewayConnection) {
        connection = state
        switch state {
        case .connected:
            if state.isReconnecting {
                // The silent reconnect ends in a new session (read below) or in `.error`: rows on screen stay meanwhile.
                if phase != .ready {
                    sessions = SessionTracker()
                    phase = .loading
                }
            } else if sessions.isNewSession(state), let generation = state.sessionGeneration {
                startRead(generation)
            }
        case .connecting:
            endSession()
            phase = .loading
        case .error(_, let cause):
            endSession()
            phase = .disconnected(reason: Self.userMessage(cause))
        case .disconnected:
            endSession()
            phase = .disconnected(reason: nil)
        }
    }

    private func apply(observed list: [InstalledSkill]) {
        // The repository emits an empty list for a socket that is no longer live: only a live session's list counts.
        guard liveGeneration != nil else { return }
        switch phase {
        case .ready:
            skills = list
        case .loading, .loadFailed:
            // A cached list shows at once; an empty one may only mean "not read on this socket yet", so the read's
            // answer decides.
            guard !list.isEmpty else { return }
            skills = list
            phase = .ready
        case .disconnected:
            break
        }
    }

    /// No session is live any more: the read in flight is dropped, the rows and a pending confirmation go, and the
    /// next session is read.
    private func endSession() {
        loadTask?.cancel()
        loadTask = nil
        isRefreshing = false
        sessions = SessionTracker()
        skills = []
        pendingUninstallSlug = nil
    }

    /// The live session's generation, `nil` while none is live (reconnecting included). Without a gateway every read
    /// counts: `0`.
    private var liveGeneration: Int? {
        guard gateway != nil else { return 0 }
        guard let connection, !connection.isReconnecting else { return nil }
        return connection.sessionGeneration
    }

    /// Reads the list of the session `generation`, replacing a read still in flight.
    @discardableResult
    private func startRead(_ generation: Int) -> Task<Void, Never> {
        loadTask?.cancel()
        if phase != .ready { phase = .loading }
        isRefreshing = true
        let task = Task { [weak self] in
            guard let self else { return }
            await self.read(generation)
        }
        loadTask = task
        return task
    }

    private func read(_ generation: Int) async {
        let result = await repo.refreshFromGateway()
        // Replaced by a newer read, or the session ended: that one owns the state.
        guard !Task.isCancelled else { return }
        isRefreshing = false
        loadTask = nil
        // Its session has ended: the connection state decides what shows.
        guard liveGeneration == generation else { return }
        switch result {
        case .success(let list):
            skills = list
            lastError = nil
            phase = .ready
        case .failure(let error):
            if phase == .ready {
                lastError = Self.userMessage(error)
            } else {
                phase = .loadFailed(message: Self.userMessage(error))
            }
        }
    }

    private static func userMessage(_ error: any Error) -> String {
        (error as? GatewayError)?.errorDescription ?? error.localizedDescription
    }

    // MARK: - Row formatting

    /// The row's version label, `v<version>`; `nil` when the gateway reported no version (a `skills.status` entry
    /// without `clawhub.installedVersion`), so the row shows only the source instead of a bare `v`.
    nonisolated static func versionLabel(_ version: String) -> String? {
        let trimmed = version.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : "v" + trimmed
    }

    // MARK: - Public API

    /// Reads the list again with `skills.status` and returns once that read ends. On failure sets ``lastError`` while
    /// rows are shown (they stay), else ``phase`` becomes `.loadFailed`. Does nothing while no session is live: a new
    /// session reads by itself.
    public func refresh() async {
        guard let generation = liveGeneration else { return }
        await startRead(generation).value
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
