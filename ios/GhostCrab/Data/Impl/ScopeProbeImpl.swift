import Foundation

/// ``ScopeProbe`` from the live session's `hello-ok.auth.scopes` (ADR-0007). No network call: the gateway reports
/// the granted scopes in the handshake, and a token-only socket (no device identity) has none.
///
/// Never throws and never returns ``ScopeProbeResult/unknownOldGateway``: disconnected →
/// ``ScopeProbeResult/failed(cause:)``.
public final class ScopeProbeImpl: ScopeProbe, Sendable {

    private let scopes: @Sendable () async -> Set<String>?

    /// - Parameter scopes: The live session's granted scopes, or `nil` while disconnected.
    public init(scopes: @escaping @Sendable () async -> Set<String>?) {
        self.scopes = scopes
    }

    /// - Parameter connectionManager: Source of the live session's scopes.
    public convenience init(connectionManager: GatewayConnectionManagerImpl) {
        self.init(scopes: { await connectionManager.grantedScopes() })
    }

    /// - Returns: ``ScopeProbeResult/known(scopes:)`` with the granted scopes (possibly empty), or
    ///   ``ScopeProbeResult/failed(cause:)`` while no session is active.
    public func probe() async -> ScopeProbeResult {
        guard let granted = await scopes() else {
            return .failed(cause: "Not connected to a gateway")
        }
        return .known(scopes: granted)
    }
}
