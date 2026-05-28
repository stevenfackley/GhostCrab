import Foundation

/// Discovers OpenClaw Gateway instances on the local network via mDNS (`NWBrowser`).
///
/// Service type: `_openclaw-gw._tcp.` Default port: 18789.
///
/// **Contract frozen at v1.0.**
///
/// > Important: The host app's `Info.plist` must declare `NSBonjourServices`
/// > containing `_openclaw-gw._tcp` or iOS silently blocks the browser.
public protocol DiscoveryService: Sendable {

    /// Starts mDNS discovery and emits each resolved gateway as it is found.
    ///
    /// - Acquires the local-network entitlement implicitly via `NWBrowser`.
    /// - Deduplicates by `instanceName`; subsequent resolutions of the same instance
    ///   are ignored.
    /// - The stream completes when `stopDiscovery()` is called or the consuming task
    ///   is cancelled.
    ///
    /// - Returns: An `AsyncStream` that emits `DiscoveredGateway` instances as they
    ///   are resolved. Does not throw; resolution failures for individual services are
    ///   silently skipped.
    func startDiscovery() -> AsyncStream<DiscoveredGateway>

    /// Stops any active mDNS discovery.
    ///
    /// Safe to call when discovery is not active.
    func stopDiscovery() async
}
