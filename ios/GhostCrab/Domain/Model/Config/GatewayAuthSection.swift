import Foundation

/// Authentication mode advertised by the gateway.
///
/// Raw values match the Kotlin enum names exactly to keep wire compatibility.
public enum AuthMode: String, Codable, Sendable, CaseIterable {
    case none
    case bearer
}

/// Typed representation of the `gateway.auth` config sub-section.
///
/// - Parameters:
///   - mode: Whether the gateway requires a bearer token.
///   - token: The bearer token value. Only meaningful when `mode` is `.bearer`.
public struct GatewayAuthSection: Codable, Sendable, Hashable {
    public let mode: AuthMode
    public let token: String?

    public init(mode: AuthMode = .none, token: String? = nil) {
        self.mode = mode
        self.token = token
    }
}
