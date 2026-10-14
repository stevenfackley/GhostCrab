import Foundation
import Observation

/// ViewModel for the iOS Settings screen.
///
/// Mirrors the Android `SettingsViewModel`, but trimmed to the wave-4-leaf scope:
/// only the cleartext-IP toggle, onboarding replay, and (read-only) app info.
/// Per-profile editing is owned by the Connection-Picker screen on iOS.
///
/// Subscribes to ``SettingsRepository/allowCleartextPublicIPs()`` via an async
/// stream launched from the view's `.task` modifier. Writes and the reset run
/// in tracked tasks (``writeTask``, ``resetTask``).
@MainActor
@Observable
public final class SettingsViewModel {

    // MARK: - Public state

    /// Current value of the "allow HTTP to public IPs" preference.
    /// Mirrors the stream's latest value.
    public private(set) var allowCleartextPublicIPs: Bool = false

    /// Transient banner — set to `true` after a successful onboarding reset.
    /// The view clears it via ``acknowledgeOnboardingReset()`` once shown.
    public private(set) var onboardingResetSuccess: Bool = false

    // MARK: - Dependencies

    private let settings: any SettingsRepository
    private let onboarding: any OnboardingRepository

    // MARK: - Tasks

    /// Latest preference write; each write awaits the previous one. Read-only outside the VM so tests can await it.
    @ObservationIgnored private(set) var writeTask: Task<Void, Never>?

    /// In-flight onboarding reset. Read-only outside the VM so tests can await it.
    @ObservationIgnored private(set) var resetTask: Task<Void, Never>?

    // MARK: - Init

    /// - Parameters:
    ///   - settings: Source of the cleartext-IP preference.
    ///   - onboarding: Used to clear the persisted completion flag when the user replays onboarding.
    public init(
        settings: any SettingsRepository,
        onboarding: any OnboardingRepository
    ) {
        self.settings = settings
        self.onboarding = onboarding
    }

    // MARK: - Observation

    /// Subscribes to the `allowCleartextPublicIPs` stream. Returns when the stream completes
    /// (typically when the underlying task is cancelled by the view's `.task` lifetime).
    ///
    /// Call from the view's `.task` modifier:
    /// ```swift
    /// .task { await vm.observe() }
    /// ```
    public func observe() async {
        for await value in settings.allowCleartextPublicIPs() {
            self.allowCleartextPublicIPs = value
        }
    }

    // MARK: - Mutations

    /// Persists the new value of the cleartext preference.
    ///
    /// Writes are chained (each awaits the previous), so rapid toggling
    /// persists values in tap order and the last tap wins.
    ///
    /// - Parameter enabled: `true` to allow HTTP connections to public IPs.
    public func setAllowCleartextPublicIPs(_ enabled: Bool) {
        // Optimistic local update for snappier toggle UX — the stream will reconfirm.
        self.allowCleartextPublicIPs = enabled
        let previous = writeTask
        writeTask = Task { [settings] in
            await previous?.value
            await settings.setAllowCleartextPublicIPs(enabled)
        }
    }

    /// Resets onboarding persistence so the walkthrough shows again on next launch.
    ///
    /// Sets ``onboardingResetSuccess`` to `true` on completion so the view can
    /// surface a confirmation and then optionally route back to onboarding.
    /// Ignored while a reset is already in flight.
    public func resetOnboarding() {
        guard resetTask == nil else { return }
        resetTask = Task { [weak self, onboarding] in
            await onboarding.reset()
            self?.onboardingResetSuccess = true
            self?.resetTask = nil
        }
    }

    /// Clears ``onboardingResetSuccess`` after the view has consumed it.
    public func acknowledgeOnboardingReset() {
        onboardingResetSuccess = false
    }
}
