import Foundation

/// Typed representation of the `gateway.mdns` config sub-section.
///
/// - Parameters:
///   - enabled: Whether the gateway advertises itself via mDNS.
///   - serviceName: mDNS service name used for discovery.
public struct GatewayMdnsSection: Codable, Sendable, Hashable {
    public let enabled: Bool
    public let serviceName: String

    public init(enabled: Bool = true, serviceName: String = "openclaw-gateway") {
        self.enabled = enabled
        self.serviceName = serviceName
    }
}
