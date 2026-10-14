import Foundation
import Observation

/// ViewModel for the multi-step onboarding walkthrough.
///
/// Mirrors `OnboardingViewModel.kt`. Persists progress through `OnboardingRepository`
/// so the user can resume after backgrounding. Navigation between steps is linear;
/// `.completed` is the terminal state that signals the host to navigate to the
/// connection picker.
///
/// Step changes apply synchronously; persistence runs in one tracked
/// ``transitionTask``. While it is in flight, further `next()` / `back()` /
/// `skip()` calls are ignored, so a double tap moves exactly one step and
/// `markCompleted()` runs once. Hydration of the saved step never overrides a
/// step the user already chose.
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

    // MARK: - Tasks

    /// Hydration of the saved step. Read-only outside the VM so tests can await it.
    @ObservationIgnored private(set) var loadTask: Task<Void, Never>?

    /// In-flight persistence of the latest transition. Read-only outside the VM so tests can await it.
    @ObservationIgnored private(set) var transitionTask: Task<Void, Never>?

    /// Set by the first user transition; hydration then leaves ``step`` alone.
    @ObservationIgnored private var userHasNavigated: Bool = false

    // MARK: - Init

    public init(repository: any OnboardingRepository) {
        self.repository = repository
        self.loadTask = Task { await self.loadSavedStep() }
    }

    // MARK: - Actions

    /// Advances to the next step in the linear sequence, persisting the change.
    /// At `.findOnNetwork`, `next()` triggers completion. Ignored while a transition is in flight.
    public func next() {
        guard transitionTask == nil, !isFinished else { return }
        userHasNavigated = true
        let target = forward(from: step)
        step = target
        transitionTask = Task {
            await self.repository.saveStep(target)
            if target == .completed { await self.finish() }
            self.transitionTask = nil
        }
    }

    /// Returns to the previous step, persisting the change. No-op at `.welcome`.
    /// Ignored while a transition is in flight.
    public func back() {
        guard transitionTask == nil, !isFinished else { return }
        userHasNavigated = true
        let target = backward(from: step)
        step = target
        transitionTask = Task {
            await self.repository.saveStep(target)
            self.transitionTask = nil
        }
    }

    /// Skips directly to completion. Marks onboarding done once, however often it is tapped.
    public func skip() {
        guard transitionTask == nil, !isFinished else { return }
        userHasNavigated = true
        transitionTask = Task {
            await self.finish()
            self.transitionTask = nil
        }
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
        guard !userHasNavigated, saved != .completed else { return }
        self.step = saved
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
