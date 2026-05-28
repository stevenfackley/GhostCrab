import Foundation

/// REST-backed ``ScopeProbe``.
///
/// The Kotlin counterpart (`ScopeProbeImpl.kt`) calls the gateway's JSON-RPC
/// `auth.whoami` over WebSocket. The iOS port hits the equivalent REST
/// endpoint exposed by ``OpenClawAPIClient/whoami()`` — `POST /api/auth/whoami`.
///
/// Mapping rules (mirrors Kotlin):
/// - Successful response → ``ScopeProbeResult/known(scopes:)``
/// - Gateway lacks the endpoint (HTTP 404 / 405) →
///   ``ScopeProbeResult/unknownOldGateway`` (analogous to JSON-RPC `-32601`)
/// - All other errors → ``ScopeProbeResult/failed(cause:)``
///
/// Never throws — the protocol declares ``probe()`` as non-throwing because
/// the caller's decision is "fall back to Phase-4 copy-paste install".
public final class ScopeProbeImpl: ScopeProbe, Sendable {

    private let connectionManager: GatewayConnectionManagerImpl

    /// - Parameter connectionManager: Concrete manager used to obtain the
    ///   active ``OpenClawAPIClient``.
    public init(connectionManager: GatewayConnectionManagerImpl) {
        self.connectionManager = connectionManager
    }

    public func probe() async -> ScopeProbeResult {
        do {
            let client = try await connectionManager.requireClient()
            let response = try await client.whoami()
            return .known(scopes: Set(response.scopes))
        } catch let e as GatewayError {
            if case let .api(_, statusCode, _) = e,
               statusCode == 404 || statusCode == 405 {
                return .unknownOldGateway
            }
            return .failed(cause: e.localizedDescription)
        } catch {
            return .failed(cause: error.localizedDescription)
        }
    }
}
