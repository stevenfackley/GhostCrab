import Foundation

/// Represents each step in the first-launch onboarding walkthrough.
///
/// `.completed` is a terminal state that indicates onboarding is done;
/// it is excluded from the visible progress indicator count.
public enum OnboardingStep: String, Codable, Sendable, CaseIterable, Hashable {
    case welcome
    case whatIsOpenClaw
    case installGateway
    case startGateway
    case verifyRunning
    case findOnNetwork
    case completed

    /// Zero-based index of the step. `.completed` maps to 6.
    public var index: Int {
        switch self {
        case .welcome: return 0
        case .whatIsOpenClaw: return 1
        case .installGateway: return 2
        case .startGateway: return 3
        case .verifyRunning: return 4
        case .findOnNetwork: return 5
        case .completed: return 6
        }
    }
}

/// Total number of visible onboarding steps, excluding `.completed`.
public let onboardingStepsCount: Int = 6
