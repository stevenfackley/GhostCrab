import Foundation

/// Manages the lifecycle of a single active gateway connection.
///
/// All state is exposed as an `AsyncStream<GatewayConnection>`. Only one connection
/// is active at a time.
///
/// **Contract frozen at v1.0.** Do not change signatures without an ADR.
public protocol GatewayConnectionManager: Sendable {

    /// Current connection state. Replays the latest value on subscribe and emits every
    /// subsequent transition. Starts at `GatewayConnection.disconnected`.
    ///
    /// Each call returns an independent stream backed by the same underlying state.
    func connectionStates() -> AsyncStream<GatewayConnection>

    /// Synchronously snapshots the current connection state without subscribing.
    var currentState: GatewayConnection { get }

    /// Determines the authentication requirement for a gateway without storing any state.
    ///
    /// Probes `GET /health` (unauthenticated) then `GET /status` (unauthenticated).
    /// - `/health` fails → throws `GatewayError.unreachable`
    /// - `/status` returns 401 or 403 → `.token`
    /// - `/status` returns 2xx → `.none`
    ///
    /// - Parameter url: Base URL of the gateway (scheme + host + port, no trailing slash).
    /// - Returns: The `AuthRequirement` inferred from the probe.
    /// - Throws: `GatewayError.unreachable` if the host is not reachable.
    /// - Throws: `GatewayError.timeout` if either probe times out.
    func probeAuth(url: String) async throws -> AuthRequirement

    /// Connects to a gateway.
    ///
    /// 1. Emits `GatewayConnection.connecting`.
    /// 2. Calls `probeAuth(url:)` to determine auth mode.
    /// 3. Instantiates an authenticated API client.
    /// 4. Fetches `/status` to read version and capabilities.
    /// 5. Emits `GatewayConnection.connected` on success.
    /// 6. On any failure: emits `GatewayConnection.error` **and** rethrows the error.
    ///
    /// Calling `connect` while already connected disconnects first.
    ///
    /// - Parameters:
    ///   - url: Base URL of the gateway.
    ///   - token: Bearer token for authentication. Pass `nil` for unauthenticated gateways.
    /// - Throws: `GatewayError.unreachable` if the host is not reachable.
    /// - Throws: `GatewayError.auth` if authentication fails.
    /// - Throws: `GatewayError.timeout` if the connection times out.
    func connect(url: String, token: String?) async throws

    /// Disconnects from the current gateway.
    ///
    /// Cancels the held API client, emits `GatewayConnection.disconnected`.
    /// No-op if already disconnected.
    func disconnect() async

    /// Problems that did not fail the current connection, or context for the current error (e.g. the device token
    /// could not be stored, or this device's identity was replaced and needs approving again). Empty while
    /// disconnected and when there is nothing to report. The default implementation reports nothing.
    ///
    /// Additive (v1.1) — see docs/adr/0005-additive-repository-methods.md
    ///
    /// - Returns: The warnings for the latest connection state.
    func connectionWarnings() async -> [ConnectionWarning]
}

extension GatewayConnectionManager {
    /// Additive (v1.1) default: no warnings.
    public func connectionWarnings() async -> [ConnectionWarning] { [] }
}
