import Foundation

/// Typed representation of the `gateway.http` config sub-section.
///
/// - Parameters:
///   - host: Bind address for the gateway HTTP server.
///   - port: TCP port the gateway listens on. Valid range: 1–65535.
public struct GatewayHttpSection: Codable, Sendable, Hashable {
    public let host: String
    public let port: Int

    public init(host: String = "0.0.0.0", port: Int = 18789) {
        self.host = host
        self.port = port
    }
}
