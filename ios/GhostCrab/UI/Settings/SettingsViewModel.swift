import Foundation
import Observation

/// ViewModel for the iOS Settings screen.
///
/// Mirrors the Android `SettingsViewModel`, but trimmed to the wave-4-leaf scope:
/// only the cleartext-IP toggle, onboarding replay, and (read-only) app info.
/// Per-profile editing is owned by the Connection-Picker screen on iOS.
///
/// Subscribes to ``SettingsRepository/allowCleartextPublicIPs()`` via an async
/// stream launched from the view's `.task` modifier — never own a `Task` here.
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
    /// - Parameter enabled: `true` to allow HTTP connections to public IPs.
    public func setAllowCleartextPublicIPs(_ enabled: Bool) {
        // Optimistic local update for snappier toggle UX — the stream will reconfirm.
        self.allowCleartextPublicIPs = enabled
        Task { [settings] in
            await settings.setAllowCleartextPublicIPs(enabled)
        }
    }

    /// Resets onboarding persistence so the walkthrough shows again on next launch.
    ///
    /// Sets ``onboardingResetSuccess`` to `true` on completion so the view can
    /// surface a confirmation and then optionally route back to onboarding.
    public func resetOnboarding() {
        Task { [weak self, onboarding] in
            await onboarding.reset()
            self?.onboardingResetSuccess = true
        }
    }

    /// Clears ``onboardingResetSuccess`` after the view has consumed it.
    public func acknowledgeOnboardingReset() {
        onboardingResetSuccess = false
    }
}
