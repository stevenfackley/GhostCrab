import Foundation

extension GatewayErrorShape {

    private static let missingScopePrefix = "missing scope: "
    private static let unknownMethodPrefix = "unknown method:"

    /// Maps a failed response onto ``GatewayError``. Branches on `code` and `details.code` (upstream's contract);
    /// the legacy `missing scope: …` / `unknown method: …` messages are a fallback only.
    ///
    /// - `details.code` `PAIRING_REQUIRED` → ``GatewayError/pairingRequired(url:requestId:reason:requestedScopes:)``
    /// - `details.code` `AUTH_*` / `DEVICE_*` → ``GatewayError/connectRejected(url:detailCode:message:recommendedNextStep:retryable:retryAfterMs:)``
    ///   with the server's retry hints
    /// - `FORBIDDEN` + `MISSING_SCOPE` (or the legacy message) → ``GatewayError/missingScope(url:method:scope:)``
    /// - `INVALID_REQUEST` `unknown method: …` → ``GatewayError/methodUnsupported(url:method:)``
    /// - anything else → ``GatewayError/rpc(url:method:code:detailCode:message:retryable:details:retryAfterMs:)``,
    ///   keeping `details` (e.g. `config.patch` validation `issues[]`) and the retry hints
    ///
    /// - Parameters:
    ///   - url: The gateway WebSocket endpoint.
    ///   - method: The RPC method that failed.
    /// - Returns: The most specific case for this error.
    func toGatewayError(url: String, method: String) -> GatewayError {
        let detail = detailCode
        if detail == "PAIRING_REQUIRED" {
            return .pairingRequired(
                url: url,
                requestId: detailString("requestId"),
                reason: detailString("reason") ?? "not-paired",
                requestedScopes: detailStrings("requestedScopes")
            )
        }
        if let detail, detail.hasPrefix("AUTH_") || detail.hasPrefix("DEVICE_") {
            return .connectRejected(
                url: url,
                detailCode: detail,
                message: message,
                recommendedNextStep: detailString("recommendedNextStep"),
                retryable: retryable ?? false,
                retryAfterMs: retryAfterMs
            )
        }
        if code == "FORBIDDEN", detail == "MISSING_SCOPE" || message.hasPrefix(Self.missingScopePrefix) {
            let fromMessage = message.hasPrefix(Self.missingScopePrefix)
                ? String(message.dropFirst(Self.missingScopePrefix.count))
                : message
            return .missingScope(url: url, method: method, scope: detailString("missingScope") ?? fromMessage)
        }
        if code == "INVALID_REQUEST", message.hasPrefix(Self.unknownMethodPrefix) {
            return .methodUnsupported(url: url, method: method)
        }
        return .rpc(
            url: url,
            method: method,
            code: code,
            detailCode: detail,
            message: message,
            retryable: retryable ?? false,
            details: detailsObject,
            retryAfterMs: retryAfterMs
        )
    }
}
