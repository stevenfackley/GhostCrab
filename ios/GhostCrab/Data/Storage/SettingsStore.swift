import Foundation

/// `UserDefaults`-backed storage for app preferences and onboarding state.
///
/// Direct port of `SettingsRepositoryImpl.kt` (the `allow_cleartext_public_ips`
/// flag) and `OnboardingRepositoryImpl.kt` (current step + completion flag).
/// Both Android files use separate DataStore files; here they share a single
/// `UserDefaults` instance with distinct, namespaced keys.
///
/// Observation is implemented by listening for `UserDefaults.didChangeNotification`
/// and re-reading each key on every post. `UserDefaults` doesn't tell us *which*
/// key changed, so observers fan out on every write — fine in practice since
/// settings change rarely and the read is cheap.
public final class SettingsStore: Sendable {

    // Namespaced keys — match the Android DataStore key names where possible.
    public static let keyAllowCleartextPublicIPs: String =
        "com.qavren.ghostcrab.settings.allow_cleartext_public_ips"
    public static let keyOnboardingCompleted: String =
        "com.qavren.ghostcrab.onboarding.completed"
    public static let keyOnboardingCurrentStep: String =
        "com.qavren.ghostcrab.onboarding.current_step"

    private let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    // ── allowCleartextPublicIPs ───────────────────────────────────────────────

    /// Whether the user has opted in to HTTP connections to public IPs.
    /// Defaults to `false`.
    public func allowCleartextPublicIPs() -> Bool {
        defaults.bool(forKey: Self.keyAllowCleartextPublicIPs)
    }

    /// Persists the "allow cleartext to public IPs" preference.
    public func setAllowCleartextPublicIPs(_ enabled: Bool) {
        defaults.set(enabled, forKey: Self.keyAllowCleartextPublicIPs)
    }

    /// Emits the current value on subscription and on every `UserDefaults`
    /// change notification.
    public func observeAllowCleartextPublicIPs() -> AsyncStream<Bool> {
        observe(key: Self.keyAllowCleartextPublicIPs) { [defaults] in
            defaults.bool(forKey: Self.keyAllowCleartextPublicIPs)
        }
    }

    // ── onboardingCompleted ───────────────────────────────────────────────────

    /// Whether the first-launch walkthrough has been completed or skipped.
    /// Defaults to `false`.
    public func onboardingCompleted() -> Bool {
        defaults.bool(forKey: Self.keyOnboardingCompleted)
    }

    /// Persists the onboarding completion flag.
    public func setOnboardingCompleted(_ completed: Bool) {
        defaults.set(completed, forKey: Self.keyOnboardingCompleted)
    }

    /// Emits the current value on subscription and on every change.
    public func observeOnboardingCompleted() -> AsyncStream<Bool> {
        observe(key: Self.keyOnboardingCompleted) { [defaults] in
            defaults.bool(forKey: Self.keyOnboardingCompleted)
        }
    }

    // ── onboardingCurrentStep ─────────────────────────────────────────────────

    /// The persisted onboarding step. Returns `nil` if nothing has been saved
    /// yet — callers (typically the repository) default to
    /// `OnboardingStep.welcome` in that case.
    public func onboardingCurrentStep() -> String? {
        defaults.string(forKey: Self.keyOnboardingCurrentStep)
    }

    /// Persists the current onboarding step's raw value.
    public func setOnboardingCurrentStep(_ rawValue: String) {
        defaults.set(rawValue, forKey: Self.keyOnboardingCurrentStep)
    }

    /// Removes the persisted onboarding step.
    public func clearOnboardingCurrentStep() {
        defaults.removeObject(forKey: Self.keyOnboardingCurrentStep)
    }

    /// Emits the current value on subscription and on every change.
    public func observeOnboardingCurrentStep() -> AsyncStream<String?> {
        observe(key: Self.keyOnboardingCurrentStep) { [defaults] in
            defaults.string(forKey: Self.keyOnboardingCurrentStep)
        }
    }

    // ── Reset ─────────────────────────────────────────────────────────────────

    /// Wipes the onboarding completion flag and current step. Used by
    /// `OnboardingRepository.reset()`.
    public func resetOnboarding() {
        defaults.removeObject(forKey: Self.keyOnboardingCompleted)
        defaults.removeObject(forKey: Self.keyOnboardingCurrentStep)
    }

    // ── Observation primitive ─────────────────────────────────────────────────

    /// Builds an `AsyncStream` that yields `read()` on subscription and on
    /// every `UserDefaults.didChangeNotification`. The `key` parameter is
    /// retained for documentation / future filtering; `UserDefaults` does not
    /// expose which key triggered the change.
    private func observe<T: Sendable>(
        key: String,
        read: @escaping @Sendable () -> T
    ) -> AsyncStream<T> {
        AsyncStream { continuation in
            continuation.yield(read())

            let observer = NotificationCenter.default.addObserver(
                forName: UserDefaults.didChangeNotification,
                object: defaults,
                queue: nil
            ) { _ in
                continuation.yield(read())
            }

            continuation.onTermination = { _ in
                NotificationCenter.default.removeObserver(observer)
            }
        }
    }
}
