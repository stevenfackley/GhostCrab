import Foundation

/// Typed representation of the top-level `gateway` config section.
///
/// - Parameters:
///   - http: HTTP server bind configuration.
///   - auth: Authentication mode and token.
///   - mdns: mDNS advertisement configuration.
public struct GatewaySection: Codable, Sendable, Hashable {
    public let http: GatewayHttpSection
    public let auth: GatewayAuthSection
    public let mdns: GatewayMdnsSection

    public init(
        http: GatewayHttpSection = GatewayHttpSection(),
        auth: GatewayAuthSection = GatewayAuthSection(),
        mdns: GatewayMdnsSection = GatewayMdnsSection()
    ) {
        self.http = http
        self.auth = auth
        self.mdns = mdns
    }
}
