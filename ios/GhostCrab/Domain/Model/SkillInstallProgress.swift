import Foundation

/// Progress reporting for an in-app skill install operation.
public enum SkillInstallProgress: Sendable, Hashable {
    case idle
    case connecting(target: String)
    case downloading(pct: Int?)
    case verifying(sha256Prefix: String)
    case applying(step: String)
    case succeeded(installed: InstalledSkill)
    case failed(error: SkillInstallError)
}
