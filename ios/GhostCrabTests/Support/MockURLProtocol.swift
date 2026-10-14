import Foundation

/// `URLProtocol` stub for `OpenClawAPIClient` tests (the analogue of Ktor's
/// `MockEngine` in `OpenClawApiClientTest.kt`).
///
/// Swift Testing runs suites in parallel, so stubs are keyed by host. Every
/// test makes its own base URL with ``uniqueBaseURL(scheme:port:)`` (or uses
/// a fixed IP literal no other test uses), registers a handler for it, and
/// reads back only its own requests. Usage:
///
///     let base = MockURLProtocol.uniqueBaseURL()
///     MockURLProtocol.register(base) { _ in .init(status: 200, json: #"{"status":"ok"}"#) }
///     let client = OpenClawAPIClient.makeForTesting(baseURL: base, protocolClasses: [MockURLProtocol.self])
final class MockURLProtocol: URLProtocol, @unchecked Sendable {

    struct Stub: Sendable {
        let status: Int
        let headers: [String: String]
        let body: Data

        init(status: Int, body: Data = Data(), headers: [String: String] = ["Content-Type": "application/json"]) {
            self.status = status
            self.body = body
            self.headers = headers
        }

        init(status: Int, json: String, headers: [String: String] = ["Content-Type": "application/json"]) {
            self.init(status: status, body: Data(json.utf8), headers: headers)
        }

        /// An HTML page, like upstream `ghcr.io/openclaw/openclaw` serves at `/status` and `/config`.
        static func html(status: Int = 200, body: String = "<!doctype html><title>OpenClaw</title>") -> Stub {
            Stub(status: status, body: Data(body.utf8), headers: ["Content-Type": "text/html; charset=utf-8"])
        }
    }

    typealias Handler = @Sendable (URLRequest) -> Stub

    private static let lock = NSLock()
    nonisolated(unsafe) private static var handlers: [String: Handler] = [:]
    nonisolated(unsafe) private static var captured: [String: [URLRequest]] = [:]

    /// A base URL whose host no other test uses. The `.local` suffix keeps
    /// the host ATS-exempt, so only this stub decides what a request returns.
    static func uniqueBaseURL(scheme: String = "http", port: Int = 18789) -> URL {
        let host = "gw-\(UUID().uuidString.lowercased()).local"
        guard let url = URL(string: "\(scheme)://\(host):\(port)") else {
            preconditionFailure("generated mock URL is always valid")
        }
        return url
    }

    /// Routes every request to `baseURL`'s host through `handler` and resets that host's capture log.
    static func register(_ baseURL: URL, handler: @escaping Handler) {
        guard let host = baseURL.host else { return }
        lock.withLock {
            handlers[host] = handler
            captured[host] = []
        }
    }

    /// Requests that reached `baseURL`'s host, in order.
    static func requests(to baseURL: URL) -> [URLRequest] {
        guard let host = baseURL.host else { return [] }
        return lock.withLock { captured[host] ?? [] }
    }

    // MARK: - URLProtocol

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url, let host = url.host else {
            client?.urlProtocol(self, didFailWithError: URLError(.badURL))
            return
        }
        let current = request
        let handler: Handler? = Self.lock.withLock {
            Self.captured[host, default: []].append(current)
            return Self.handlers[host]
        }
        guard let handler else {
            client?.urlProtocol(self, didFailWithError: URLError(.cannotFindHost))
            return
        }
        let stub = handler(current)
        guard let response = HTTPURLResponse(url: url, statusCode: stub.status, httpVersion: "HTTP/1.1", headerFields: stub.headers) else {
            client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse))
            return
        }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: stub.body)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
