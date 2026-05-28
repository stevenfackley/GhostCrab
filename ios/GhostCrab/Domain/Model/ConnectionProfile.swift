import Foundation

/// A saved gateway connection profile stored locally.
///
/// Metadata persists in `UserDefaults`. The bearer token, if any,
/// is stored separately in the iOS Keychain keyed by `id`.
///
/// - Parameters:
///   - id: UUID unique to this profile.
///   - displayName: User-visible label (defaulted to hostname:port on creation).
///   - url: Base URL of the gateway.
///   - lastConnectedAt: Timestamp of last successful connection, or `nil` if never.
///   - hasToken: Whether a token is stored in the Keychain for this profile.
public struct ConnectionProfile: Codable, Sendable, Identifiable, Hashable {
    public let id: UUID
    public let displayName: String
    public let url: String
    public let lastConnectedAt: Date?
    public let hasToken: Bool

    public init(
        id: UUID,
        displayName: String,
        url: String,
        lastConnectedAt: Date?,
        hasToken: Bool
    ) {
        self.id = id
        self.displayName = displayName
        self.url = url
        self.lastConnectedAt = lastConnectedAt
        self.hasToken = hasToken
    }
}
