import Foundation

/// A `URLProtocol` subclass that returns canned responses for tests.
///
/// Equivalent in spirit to Ktor's `MockEngine` (`app/src/test/.../OpenClawApiClientTest.kt`).
/// Tests configure a `URLSession` with `Self.self` registered in
/// `configuration.protocolClasses`, then set ``stub`` to define the response shape.
///
/// **Usage:**
/// ```swift
/// MockURLProtocol.stub = .init(status: 200, json: """{"status":true}""")
/// let session = MockURLProtocol.makeSession()
/// let client = OpenClawAPIClient.forTest(baseURL: URL(string: "http://x")!, session: session)
/// ```
final class MockURLProtocol: URLProtocol, @unchecked Sendable {

    struct Stub: Sendable {
        let status: Int
        let headers: [String: String]
        let body: Data

        init(status: Int, body: Data, headers: [String: String] = ["Content-Type": "application/json"]) {
            self.status = status
            self.body = body
            self.headers = headers
        }

        init(status: Int, json: String, headers: [String: String] = ["Content-Type": "application/json"]) {
            self.init(status: status, body: Data(json.utf8), headers: headers)
        }
    }

    // MARK: - Per-test configuration

    /// The response to return for every request handled by this protocol.
    /// Reset before each test via `MockURLProtocol.reset()`.
    nonisolated(unsafe) static var stub: Stub = .init(status: 200, json: "{}")

    /// Captures every request that came through, in order. Cleared by `reset()`.
    nonisolated(unsafe) static var capturedRequests: [URLRequest] = []

    static func reset() {
        stub = .init(status: 200, json: "{}")
        capturedRequests.removeAll()
    }

    /// Returns a session configured to route every request through this protocol.
    static func makeSession() -> URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [Self.self]
        return URLSession(configuration: config)
    }

    // MARK: - URLProtocol overrides

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.capturedRequests.append(request)
        let stub = Self.stub

        let response = HTTPURLResponse(
            url: request.url!,
            statusCode: stub.status,
            httpVersion: "HTTP/1.1",
            headerFields: stub.headers
        )!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: stub.body)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {
        // No-op — responses are synchronous in startLoading.
    }
}
