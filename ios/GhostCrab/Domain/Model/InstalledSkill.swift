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
    /// `true` when the gateway reports the skill disabled (`skills.status` `disabled`). Additive (v1.1).
    /// (`installedAt` comes from `clawhub.installedAt` for ClawHub installs; the epoch otherwise.)
    public let disabled: Bool

    /// `Identifiable` uses `slug` as the stable identifier.
    public var id: String { slug }

    public init(
        slug: String,
        installedVersion: String,
        source: SkillSource,
        installedAt: Date,
        disabled: Bool = false
    ) {
        self.slug = slug
        self.installedVersion = installedVersion
        self.source = source
        self.installedAt = installedAt
        self.disabled = disabled
    }
}

extension InstalledSkill {
    /// Decodes `disabled` leniently: a value encoded before it existed (v1.0) has none and reads as enabled.
    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            slug: try container.decode(String.self, forKey: .slug),
            installedVersion: try container.decode(String.self, forKey: .installedVersion),
            source: try container.decode(SkillSource.self, forKey: .source),
            installedAt: try container.decode(Date.self, forKey: .installedAt),
            disabled: try container.decodeIfPresent(Bool.self, forKey: .disabled) ?? false
        )
    }
}
