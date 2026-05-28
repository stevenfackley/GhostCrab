import Foundation

/// A gateway discovered via mDNS (NWBrowser / Bonjour).
///
/// Service type: `_openclaw-gw._tcp.` Default port: 18789.
/// Deduplication key: `instanceName`.
///
/// - Parameters:
///   - instanceName: mDNS service instance name (unique per gateway on the network).
///   - hostAddress: Resolved IPv4/IPv6 address.
///   - port: TCP port the gateway is listening on.
///   - displayName: Human-readable name from TXT record or instance name fallback.
///   - version: Gateway version from TXT record, or `nil` if not advertised.
public struct DiscoveredGateway: Codable, Sendable, Hashable {
    public let instanceName: String
    public let hostAddress: String
    public let port: Int
    public let displayName: String
    public let version: String?

    public init(
        instanceName: String,
        hostAddress: String,
        port: Int,
        displayName: String,
        version: String?
    ) {
        self.instanceName = instanceName
        self.hostAddress = hostAddress
        self.port = port
        self.displayName = displayName
        self.version = version
    }

    /// Constructed base URL for use with `GatewayConnectionManager`.
    public var url: String { "http://\(hostAddress):\(port)" }
}
