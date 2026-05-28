import Foundation

/// Wire-type DTO for `GET /health`.
///
/// The upstream `ghcr.io/openclaw/openclaw:latest` build sometimes returns
/// `{"status": true}` (boolean) instead of `{"status": "ok"}` (string).
/// This decoder accepts both — booleans are coerced to `"true"`/`"false"`.
public struct HealthResponse: Codable, Sendable, Equatable {

    /// Status string. `"ok"` or `"true"` indicates a healthy gateway.
    public let status: String

    public init(status: String) {
        self.status = status
    }

    private enum CodingKeys: String, CodingKey { case status }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        if let s = try? container.decode(String.self, forKey: .status) {
            self.status = s
        } else if let b = try? container.decode(Bool.self, forKey: .status) {
            self.status = b ? "true" : "false"
        } else {
            throw DecodingError.dataCorruptedError(
                forKey: .status,
                in: container,
                debugDescription: "HealthResponse.status must be a String or Bool"
            )
        }
    }
}
