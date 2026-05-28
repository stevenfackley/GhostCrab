import Foundation

/// Persists onboarding progress across app sessions.
///
/// All methods are safe to call from any task / actor.
public protocol OnboardingRepository: Sendable {

    /// Returns `true` if the user has previously completed or skipped onboarding.
    func isCompleted() async -> Bool

    /// Saves the current `step` so it can be resumed on next launch.
    ///
    /// - Parameter step: The step to persist. Passing `OnboardingStep.completed` does
    ///   not automatically set the completed flag — use `markCompleted()` for that.
    func saveStep(_ step: OnboardingStep) async

    /// Returns the last-saved step, or `OnboardingStep.welcome` if none stored.
    func getSavedStep() async -> OnboardingStep

    /// Marks onboarding as fully completed. Subsequent calls to `isCompleted()` return `true`.
    func markCompleted() async

    /// Resets all onboarding state so the walkthrough will be shown again on next launch.
    func reset() async
}
