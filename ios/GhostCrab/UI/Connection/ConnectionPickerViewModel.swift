import Foundation
import Observation

/// ViewModel for the connection picker screen.
///
/// Mirrors `ConnectionPickerViewModel.kt`. Observes the saved-profile list via
/// `ConnectionProfileRepository.observeProfiles()` and exposes a `connect(...)`
/// action that loads the token from the keychain and attempts to bring up the
/// gateway connection. A saved gateway that answers `PAIRING_REQUIRED` (it forgot
/// the device, or a scope or metadata upgrade) waits for approval with the same
/// ``PairingWait`` and card as Manual Entry (ADR-0007).
@MainActor
@Observable
public final class ConnectionPickerViewModel {

    // MARK: - Dependencies

    private let profileRepository: any ConnectionProfileRepository
    private let connectionManager: any GatewayConnectionManager
    /// The connect and the device-approval wait it may turn into; one at a time.
    private let pairing: PairingWait

    // MARK: - State

    /// Saved profiles, replays on every change.
    public private(set) var profiles: [ConnectionProfile] = []

    /// The profile id currently being connected, or `nil` if no connect is in flight.
    public private(set) var connectingId: UUID?

    /// Current connection state of the manager — drives `ConnectionStatusBar`.
    public private(set) var connectionState: GatewayConnection = .disconnected

    /// Last error surfaced from a connect attempt. Cleared on the next attempt.
    public var lastError: String?

    /// Set to a non-nil profile id when a connect attempt has succeeded; the view
    /// observes this and pushes `.dashboard`.
    public private(set) var navigateToDashboard: Bool = false

    /// Set while a connect waits for device approval on the gateway host (ADR-0007); the screen shows
    /// ``PairingApprovalCard`` and hides its QR / LAN / Add actions. `nil` otherwise.
    public private(set) var pairingPrompt: PairingPrompt?

    /// `false` while a connect, or the approval wait it turned into, is running: the rows are then inert (dimmed, no
    /// tap, no swipe-to-delete) rather than taking a tap that ``connect(_:)`` would ignore.
    public var profilesSelectable: Bool { connectingId == nil }

    @ObservationIgnored
    private var observeTask: Task<Void, Never>?
    @ObservationIgnored
    private var stateTask: Task<Void, Never>?

    /// In-flight connect (and approval wait), if any. Tests await it.
    var connectTask: Task<Void, Never>? { pairing.task }

    /// Most recent delete. Read-only outside the VM so tests can await it.
    @ObservationIgnored private(set) var deleteTask: Task<Void, Never>?

    /// Profile ids with a delete in flight; a second swipe on the same row is ignored.
    @ObservationIgnored private var pendingDeletes: Set<UUID> = []

    // MARK: - Init

    /// - Parameters:
    ///   - profileRepository: Saved profiles and their tokens.
    ///   - connectionManager: Drives the connect flow.
    ///   - pairingRetryInterval: Delay between connects while awaiting device approval.
    ///   - pairingTimeout: How long to wait for approval (upstream expires the request after 5 minutes).
    public init(
        profileRepository: any ConnectionProfileRepository,
        connectionManager: any GatewayConnectionManager,
        pairingRetryInterval: Duration = PairingApproval.retryInterval,
        pairingTimeout: Duration = PairingApproval.timeout
    ) {
        self.profileRepository = profileRepository
        self.connectionManager = connectionManager
        self.pairing = PairingWait(
            connectionManager: connectionManager, retryInterval: pairingRetryInterval, timeout: pairingTimeout
        )
        self.connectionState = connectionManager.currentState
        self.startObserving()
    }

    deinit {
        observeTask?.cancel()
        stateTask?.cancel()
    }

    // MARK: - Observation

    private func startObserving() {
        observeTask?.cancel()
        let profileStream = profileRepository.observeProfiles()
        observeTask = Task { [weak self] in
            for await list in profileStream {
                guard let self else { return }
                await MainActor.run { self.profiles = list }
            }
        }

        stateTask?.cancel()
        let stateStream = connectionManager.connectionStates()
        stateTask = Task { [weak self] in
            for await s in stateStream {
                guard let self else { return }
                await MainActor.run { self.connectionState = s }
            }
        }
    }

    // MARK: - Actions

    /// Attempts to connect to the given saved profile with its stored token, read
    /// once. On success, flips `navigateToDashboard` so the view can push
    /// `.dashboard`. On `PAIRING_REQUIRED` sets ``pairingPrompt`` and retries until
    /// the device is approved, the pending request expires
    /// (``PairingApproval/timeoutMessage``) or ``cancelPairing()``. A connect that
    /// another screen superseded ends without an error. Ignored while a connect is
    /// already in flight, so a double tap connects once.
    public func connect(_ profile: ConnectionProfile) {
        guard !pairing.isRunning else { return }
        self.connectingId = profile.id
        self.lastError = nil
        self.pairingPrompt = nil
        let repository = profileRepository
        pairing.start(
            url: profile.url,
            token: {
                guard profile.hasToken else { return nil }
                return try await repository.getToken(profileId: profile.id.uuidString)
            },
            onPrompt: { [weak self] prompt in self?.pairingPrompt = prompt },
            onFinished: { [weak self] result in self?.finishConnect(result) }
        )
    }

    /// "Check now" on the approval card: re-attempts the connect at once.
    public func checkPairingNow() {
        pairing.checkNow()
    }

    /// "Cancel" on the approval card, or the screen being left: stops the connect
    /// or the wait. No error is shown; a wait that still holds its
    /// `PAIRING_REQUIRED` error is disconnected (see ``PairingWait/cancel()``).
    /// No-op when nothing is in flight.
    public func cancelPairing() {
        guard pairing.isRunning else { return }
        pairing.cancel()
        connectingId = nil
        pairingPrompt = nil
    }

    /// The connect (or its approval wait) ended: navigate, or say why not.
    private func finishConnect(_ result: PairingWaitResult) {
        pairingPrompt = nil
        connectingId = nil
        switch result {
        case .connected:
            navigateToDashboard = true
        case .expired:
            lastError = PairingApproval.timeoutMessage
        case .superseded:
            // Another screen acted on the connection meanwhile: nothing to report.
            break
        case .failed(let error):
            lastError = (error as? LocalizedError)?.errorDescription
                ?? "Connection failed: \(String(describing: error))"
        }
    }

    /// Resets the navigation flag after the host has pushed `.dashboard`.
    public func consumeNavigation() {
        self.navigateToDashboard = false
    }

    /// Deletes a saved profile and its associated keychain token. A repeat
    /// delete of the same profile while the first is in flight is ignored.
    public func delete(_ profileId: UUID) {
        guard pendingDeletes.insert(profileId).inserted else { return }
        deleteTask = Task {
            defer { self.pendingDeletes.remove(profileId) }
            do {
                try await self.profileRepository.deleteProfile(profileId: profileId.uuidString)
            } catch {
                // Prefer the error's own copy (e.g. the corrupt-list advice for a delete).
                let detail = (error as? LocalizedError)?.errorDescription ?? String(describing: error)
                self.lastError = "Failed to delete: \(detail)"
            }
        }
    }
}
