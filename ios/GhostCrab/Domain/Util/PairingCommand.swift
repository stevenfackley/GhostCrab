import Foundation

/// The `openclaw devices …` command the gateway owner runs to approve this device (ADR-0007 decision 7). Shared by
/// the approval card (`PairingPrompt`) and ``GatewayError/pairingRequired(url:requestId:reason:requestedScopes:)``'s
/// message, so both apply the same shell-safety filter to the request id the gateway sent.
public enum PairingCommand {

    /// Longest request id kept; real ids are UUIDs.
    static let maxRequestIdLength = 128

    /// The command when the gateway sent no usable request id.
    public static let list = "openclaw devices list"

    /// - Parameter requestId: The pending request id the gateway sent, if any.
    /// - Returns: `requestId` when it is 1–128 ASCII letters, digits, `-`, `_`, `.` or `:` starting with a letter or
    ///   digit; else `nil`. The command is copied into a shell on the gateway host, so a spoofed id must not smuggle
    ///   shell syntax into it, nor turn into an option (`--help`).
    public static func shellSafeRequestId(_ requestId: String?) -> String? {
        guard let id = requestId, let first = id.first, first.isASCII, first.isLetter || first.isNumber,
              id.count <= maxRequestIdLength,
              id.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || "-_.:".contains($0)) })
        else { return nil }
        return id
    }

    /// - Parameter requestId: The pending request id the gateway sent, if any.
    /// - Returns: `openclaw devices approve <requestId>` for a shell-safe id (``shellSafeRequestId(_:)``), else
    ///   ``list``.
    public static func approve(requestId: String?) -> String {
        shellSafeRequestId(requestId).map { "openclaw devices approve \($0)" } ?? list
    }
}
