import Foundation

/// The control-plane socket URL for a gateway base URL (ADR-0007), with the app's cleartext policy applied.
/// ``CleartextPublicIPGuard`` is a `URLProtocol` and never sees `URLSessionWebSocketTask`s, so the same rule is
/// enforced here before a socket is built. ATS still applies to `ws://` (ADR-0006).
public enum GatewayWebSocketURL {

    /// Path the gateway serves its WebSocket on.
    public static let path = "/ws"

    /// - Parameters:
    ///   - baseURL: `http(s)://host:port`; any path, query or fragment is replaced.
    ///   - allowCleartextPublicIPs: The Settings opt-in.
    /// - Returns: `ws(s)://host:port/ws`.
    /// - Throws: ``GatewayError/cleartextBlocked(url:host:)`` for `ws://` to a public IP literal without the
    ///   opt-in; ``GatewayError/api(url:statusCode:body:)`` (status 0) when `baseURL` is not `http(s)` with a host.
    public static func make(from baseURL: URL, allowCleartextPublicIPs: Bool) throws -> URL {
        guard var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false),
              let scheme = components.scheme?.lowercased(), scheme == "http" || scheme == "https",
              let host = components.host, !host.isEmpty
        else {
            throw GatewayError.api(url: baseURL.absoluteString, statusCode: 0, body: "Not an http(s) gateway URL")
        }
        components.scheme = scheme == "https" ? "wss" : "ws"
        components.path = path
        components.query = nil
        components.fragment = nil
        guard let url = components.url else {
            throw GatewayError.api(url: baseURL.absoluteString, statusCode: 0, body: "Malformed URL")
        }
        if scheme == "http", !allowCleartextPublicIPs, CleartextPublicIPGuard.isPublicIPLiteral(host) {
            throw GatewayError.cleartextBlocked(url: url.absoluteString, host: host)
        }
        return url
    }
}
