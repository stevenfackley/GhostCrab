import Foundation

/// Persists app-level user preferences.
///
/// **Contract frozen at v1.0.**
public protocol SettingsRepository: Sendable {

    /// Emits whether the user has opted in to HTTP connections to public (non-LAN) IP addresses.
    ///
    /// Default: `false` (cleartext to public IPs is blocked by default).
    ///
    /// Replays the current value on subscription, then emits every change.
    func allowCleartextPublicIPs() -> AsyncStream<Bool>

    /// Persists the "allow cleartext to public IPs" preference.
    ///
    /// - Parameter enabled: `true` to allow HTTP connections to public IPs.
    func setAllowCleartextPublicIPs(_ enabled: Bool) async
}
