import Foundation
import Observation

/// ViewModel for the multi-step onboarding walkthrough.
///
/// Mirrors `OnboardingViewModel.kt`. Persists progress through `OnboardingRepository`
/// so the user can resume after backgrounding. Navigation between steps is linear;
/// `.completed` is the terminal state that signals the host to navigate to the
/// connection picker.
@MainActor
@Observable
public final class OnboardingViewModel {

    // MARK: - Dependencies

    private let repository: any OnboardingRepository

    // MARK: - State

    /// The current visible step. Starts at `.welcome`; hydrated from repository on load.
    public private(set) var step: OnboardingStep = .welcome

    /// `true` once `markCompleted()` has finished running. The host view observes
    /// this and pushes `.connectionPicker` (matching the Android `onDone` callback).
    public private(set) var isFinished: Bool = false

    // MARK: - Init

    public init(repository: any OnboardingRepository) {
        self.repository = repository
        Task { await self.loadSavedStep() }
    }

    // MARK: - Actions

    /// Advances to the next step in the linear sequence, persisting the change.
    /// At `.findOnNetwork`, `next()` triggers completion.
    public func next() {
        Task {
            let target = forward(from: self.step)
            self.step = target
            await self.repository.saveStep(target)
            if target == .completed { await self.finish() }
        }
    }

    /// Returns to the previous step, persisting the change. No-op at `.welcome`.
    public func back() {
        Task {
            let target = backward(from: self.step)
            self.step = target
            await self.repository.saveStep(target)
        }
    }

    /// Skips directly to completion. Marks onboarding done.
    public func skip() {
        Task { await self.finish() }
    }

    /// Marks onboarding as completed in repository and flips `isFinished`.
    private func finish() async {
        self.step = .completed
        await self.repository.markCompleted()
        self.isFinished = true
    }

    // MARK: - Hydration

    private func loadSavedStep() async {
        let saved = await self.repository.getSavedStep()
        if saved != .completed { self.step = saved }
    }

    // MARK: - Pure transitions (mirror Kotlin `advance()` blocks)

    private func forward(from current: OnboardingStep) -> OnboardingStep {
        switch current {
        case .welcome:        return .whatIsOpenClaw
        case .whatIsOpenClaw: return .installGateway
        case .installGateway: return .startGateway
        case .startGateway:   return .verifyRunning
        case .verifyRunning:  return .findOnNetwork
        case .findOnNetwork:  return .completed
        case .completed:      return .completed
        }
    }

    private func backward(from current: OnboardingStep) -> OnboardingStep {
        switch current {
        case .welcome:        return .welcome
        case .whatIsOpenClaw: return .welcome
        case .installGateway: return .whatIsOpenClaw
        case .startGateway:   return .installGateway
        case .verifyRunning:  return .startGateway
        case .findOnNetwork:  return .verifyRunning
        case .completed:      return .findOnNetwork
        }
    }
}
