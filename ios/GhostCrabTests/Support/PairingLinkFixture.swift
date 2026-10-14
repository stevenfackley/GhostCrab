import Foundation

/// Builds `ghostcrab://pair?p=<base64url>` links the way the gateway pairing page does.
enum PairingLinkFixture {

    /// Wraps `json` as unpadded base64url in a `ghostcrab://pair?p=` link.
    static func link(json: String) -> String {
        let base64url = Data(json.utf8).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        return "ghostcrab://pair?p=\(base64url)"
    }
}
