import Foundation
import Observation

/// ViewModel for the connection picker screen.
///
/// Mirrors `ConnectionPickerViewModel.kt`. Observes the saved-profile list via
/// `ConnectionProfileRepository.observeProfiles()` and exposes a `connect(...)`
/// action that loads the token from the keychain and attempts to bring up the
/// gateway connection.
@MainActor
@Observable
public final class ConnectionPickerViewModel {

    // MARK: - Dependencies

    private let profileRepository: any ConnectionProfileRepository
    private let connectionManager: any GatewayConnectionManager

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

    private var observeTask: Task<Void, Never>?
    private var stateTask: Task<Void, Never>?

    // MARK: - Init

    public init(
        profileRepository: any ConnectionProfileRepository,
        connectionManager: any GatewayConnectionManager
    ) {
        self.profileRepository = profileRepository
        self.connectionManager = connectionManager
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

    /// Attempts to connect to the given saved profile. On success, flips
    /// `navigateToDashboard` so the view can push `.dashboard`.
    public func connect(_ profile: ConnectionProfile) {
        self.connectingId = profile.id
        self.lastError = nil
        Task {
            do {
                let token = profile.hasToken
                    ? try await self.profileRepository.getToken(profileId: profile.id.uuidString)
                    : nil
                try await self.connectionManager.connect(url: profile.url, token: token)
                self.connectingId = nil
                self.navigateToDashboard = true
            } catch {
                self.connectingId = nil
                self.lastError = (error as? LocalizedError)?.errorDescription
                    ?? "Connection failed: \(String(describing: error))"
            }
        }
    }

    /// Resets the navigation flag after the host has pushed `.dashboard`.
    public func consumeNavigation() {
        self.navigateToDashboard = false
    }

    /// Deletes a saved profile and its associated keychain token.
    public func delete(_ profileId: UUID) {
        Task {
            do {
                try await self.profileRepository.deleteProfile(profileId: profileId.uuidString)
            } catch {
                self.lastError = "Failed to delete: \(String(describing: error))"
            }
        }
    }
}
