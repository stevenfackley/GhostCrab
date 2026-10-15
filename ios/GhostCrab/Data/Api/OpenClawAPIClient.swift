import Foundation
import os

/// URLSession-backed HTTP client for the one JSON REST route GhostCrab uses: `GET /health` (ADR-0007). Everything
/// else goes over the control-plane WebSocket (``GatewayRPCClient``), and no credential is ever sent over HTTP.
/// Requests pass through ``CleartextPublicIPGuard``. Call ``close()`` when the session ends.
public actor OpenClawAPIClient {

    /// Request timeout policy. `URLRequest.timeoutInterval` is an idle timeout (it covers connection setup), so a
    /// dead or black-holed host fails after ``standard`` seconds; ``resource`` caps a whole transfer.
    enum Timeouts {
        /// `/health`.
        static let standard: TimeInterval = 15
        /// Upper bound for any single transfer.
        static let resource: TimeInterval = 180
    }

    // MARK: - State

    public let baseURL: URL
    /// This client's cleartext-to-public-IP opt-in; attached to every request in ``send(_:timeout:)``.
    private let allowCleartextPublicIPs: Bool
    private let session: URLSession
    private let decoder = JSONDecoder()

    private static let logger = Logger(subsystem: "com.qavren.ghostcrab", category: "OpenClawAPIClient")

    private init(baseURL: URL, allowCleartextPublicIPs: Bool, additionalProtocolClasses: [AnyClass] = []) {
        self.baseURL = baseURL
        self.allowCleartextPublicIPs = allowCleartextPublicIPs
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = Timeouts.standard
        config.timeoutIntervalForResource = Timeouts.resource
        config.waitsForConnectivity = false
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.protocolClasses = [CleartextPublicIPGuard.self] + additionalProtocolClasses + (config.protocolClasses ?? [])
        self.session = URLSession(configuration: config)
    }

    // MARK: - Factories

    /// - Parameters:
    ///   - baseURL: Gateway base URL (no trailing slash).
    ///   - allowCleartextPublicIPs: If `true`, the cleartext-public-IP guard is bypassed.
    public static func unauthenticated(baseURL: URL, allowCleartextPublicIPs: Bool = false) -> OpenClawAPIClient {
        OpenClawAPIClient(baseURL: baseURL, allowCleartextPublicIPs: allowCleartextPublicIPs)
    }

    /// Test seam: the production client plus extra `URLProtocol` classes (e.g. `MockURLProtocol`) consulted after
    /// ``CleartextPublicIPGuard``, so guard behaviour stays under test. Internal, so only reachable through
    /// `@testable import`.
    static func makeForTesting(baseURL: URL, allowCleartextPublicIPs: Bool = false, protocolClasses: [AnyClass]) -> OpenClawAPIClient {
        OpenClawAPIClient(
            baseURL: baseURL,
            allowCleartextPublicIPs: allowCleartextPublicIPs,
            additionalProtocolClasses: protocolClasses
        )
    }

    // MARK: - Public API

    /// `GET /health` — unauthenticated liveness check, run before the socket opens.
    ///
    /// - Returns: The decoded body; a non-JSON body still means the process answered and reads as `"ok"`.
    /// - Throws: `GatewayError.unreachable`, `.timeout`, `.tls`, `.cleartextBlocked`, or `.api` / `.auth` for a
    ///   non-2xx status; `CancellationError` when the calling task is cancelled.
    public func health() async throws -> HealthResponse {
        let url = endpoint("/health")
        return try await safe(url: url) {
            let (data, response) = try await self.send(URLRequest(url: url))
            try self.mapErrors(url: url, response: response)
            // Tolerant: a non-JSON body still means the process answered.
            if let decoded = try? self.decoder.decode(HealthResponse.self, from: data) {
                return decoded
            }
            return HealthResponse(status: "ok")
        }
    }

    /// Releases the underlying URLSession. Call on disconnect.
    public func close() {
        session.invalidateAndCancel()
    }

    // MARK: - Helpers

    private func endpoint(_ path: String) -> URL {
        // Avoid a double slash if the caller passed a URL with a trailing slash.
        let trimmed = baseURL.absoluteString.hasSuffix("/")
            ? String(baseURL.absoluteString.dropLast())
            : baseURL.absoluteString
        return URL(string: trimmed + path) ?? baseURL.appendingPathComponent(path)
    }

    /// Sends `request` with this client's cleartext opt-in attached for ``CleartextPublicIPGuard``.
    private func send(_ request: URLRequest, timeout: TimeInterval = Timeouts.standard) async throws -> (Data, URLResponse) {
        var req = request
        req.timeoutInterval = timeout
        req = CleartextPublicIPGuard.tagged(req, allowCleartextPublicIPs: allowCleartextPublicIPs)
        Self.logDebug(req)
        return try await session.data(for: req)
    }

    /// Maps an HTTP response into a `GatewayError`. No-op on 2xx.
    nonisolated private func mapErrors(url: URL, response: URLResponse) throws {
        guard let http = response as? HTTPURLResponse else { return }
        let code = http.statusCode
        if (200..<300).contains(code) { return }
        switch code {
        case 401, 403:
            throw GatewayError.auth(url: url.absoluteString, statusCode: code)
        default:
            throw GatewayError.api(url: url.absoluteString, statusCode: code)
        }
    }

    /// Same error-translation policy as before ADR-0007: a request ``CleartextPublicIPGuard`` refused becomes
    /// ``GatewayError/cleartextBlocked(url:host:)``, never `.unreachable`; a cancelled task stays a cancellation.
    private func safe<T>(url: URL, _ block: () async throws -> T) async throws -> T {
        do {
            return try await block()
        } catch let e as GatewayError {
            throw e
        } catch let e as URLError {
            switch e.code {
            case .cancelled where Task.isCancelled:
                // URLSession reports a cancelled Swift task as URLError.cancelled. The caller went away (Back while
                // connecting): that is a cancellation, not an unreachable gateway.
                throw CancellationError()
            case .timedOut:
                throw GatewayError.timeout(url: url.absoluteString, underlying: e)
            case .serverCertificateHasBadDate,
                 .serverCertificateUntrusted,
                 .serverCertificateHasUnknownRoot,
                 .serverCertificateNotYetValid,
                 .clientCertificateRejected,
                 .clientCertificateRequired,
                 .secureConnectionFailed:
                throw GatewayError.tls(url: url.absoluteString, underlying: e)
            default:
                throw GatewayError.unreachable(url: url.absoluteString, underlying: e)
            }
        } catch is CancellationError {
            throw CancellationError()
        } catch let e as NSError where e.domain == CleartextPublicIPGuard.errorDomain {
            // Refused by our own policy before leaving the device: say so, and say how to lift it, instead of
            // blaming the network.
            let host = (e.userInfo[CleartextPublicIPGuard.hostUserInfoKey] as? String) ?? url.host ?? "<unknown>"
            throw GatewayError.cleartextBlocked(url: url.absoluteString, host: host)
        } catch {
            throw GatewayError.unreachable(url: url.absoluteString, underlying: error)
        }
    }

    // MARK: - Logging

    /// Logs method, URL and header names at debug level. No credential is attached to any request; an
    /// `Authorization` header would still be left out.
    nonisolated private static func logDebug(_ req: URLRequest) {
        #if DEBUG
        let method = req.httpMethod ?? "GET"
        let url = req.url?.absoluteString ?? "<nil>"
        var headerNames = req.allHTTPHeaderFields?.keys.map { $0 } ?? []
        headerNames.removeAll { $0.caseInsensitiveCompare("Authorization") == .orderedSame }
        logger.debug("\(method, privacy: .public) \(url, privacy: .public) headers=\(headerNames.joined(separator: ","), privacy: .public)")
        #endif
    }
}
