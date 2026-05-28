import Foundation

/// ``SettingsStore``-backed implementation of ``OnboardingRepository``.
///
/// Direct port of `OnboardingRepositoryImpl.kt`. The Kotlin version uses a
/// dedicated `"onboarding"` DataStore file; here the same ``SettingsStore``
/// instance backs both app settings and onboarding state via namespaced keys
/// (`com.qavren.ghostcrab.onboarding.completed` /
/// `com.qavren.ghostcrab.onboarding.current_step`).
///
/// The `markCompleted()` method sets the completion flag *and* persists
/// ``OnboardingStep/completed`` as the last step, matching the Kotlin
/// behaviour. `saveStep` persists the enum's `rawValue` — the same string
/// that ``OnboardingStep`` round-trips through `Codable`.
public final class OnboardingRepositoryImpl: OnboardingRepository, Sendable {

    private let store: SettingsStore

    /// - Parameter store: Backing `UserDefaults` wrapper.
    public init(store: SettingsStore) {
        self.store = store
    }

    /// Returns `true` if the user has previously completed or skipped onboarding.
    public func isCompleted() async -> Bool {
        store.onboardingCompleted()
    }

    /// Saves the current onboarding `step` so it can be resumed on next launch.
    ///
    /// - Parameter step: The step to persist. Passing ``OnboardingStep/completed``
    ///   does *not* automatically set the completed flag — use ``markCompleted()``
    ///   for that.
    public func saveStep(_ step: OnboardingStep) async {
        store.setOnboardingCurrentStep(step.rawValue)
    }

    /// Returns the last-saved step, or ``OnboardingStep/welcome`` if none is
    /// stored (or the stored value can no longer be decoded — e.g. a future/
    /// renamed case — in which case we restart from the beginning rather than
    /// crash, mirroring the Kotlin `stepFromName` fallback).
    public func getSavedStep() async -> OnboardingStep {
        guard let raw = store.onboardingCurrentStep(),
              let step = OnboardingStep(rawValue: raw)
        else {
            return .welcome
        }
        return step
    }

    /// Marks onboarding as fully completed. Subsequent calls to ``isCompleted()``
    /// return `true`. Also persists ``OnboardingStep/completed`` as the current
    /// step so ``getSavedStep()`` reflects the terminal state.
    public func markCompleted() async {
        store.setOnboardingCompleted(true)
        store.setOnboardingCurrentStep(OnboardingStep.completed.rawValue)
    }

    /// Resets all onboarding state so the walkthrough will be shown again on
    /// next launch.
    public func reset() async {
        store.resetOnboarding()
    }
}
