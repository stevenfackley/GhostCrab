import Foundation

/// Origin of an installed skill.
public enum SkillSource: String, Codable, Sendable, CaseIterable {
    case clawHub = "ClawHub"
    case local = "Local"
    case unknown = "Unknown"
}

/// A skill installed on the gateway.
public struct InstalledSkill: Codable, Sendable, Identifiable, Hashable {
    public let slug: String
    public let installedVersion: String
    public let source: SkillSource
    public let installedAt: Date

    /// `Identifiable` uses `slug` as the stable identifier.
    public var id: String { slug }

    public init(
        slug: String,
        installedVersion: String,
        source: SkillSource,
        installedAt: Date
    ) {
        self.slug = slug
        self.installedVersion = installedVersion
        self.source = source
        self.installedAt = installedAt
    }
}
