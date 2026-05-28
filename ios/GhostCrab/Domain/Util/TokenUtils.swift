import Foundation

/// Pure helpers for token generation.
///
/// Namespaced via a case-less `enum` (Swift idiom — cannot be instantiated).
public enum TokenUtils {

    /// Generates a cryptographically random 32-byte bearer token encoded as URL-safe Base64.
    ///
    /// - Returns: A 43-character base64url string (no padding) suitable for use as a bearer token.
    public static func generateToken() -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        let status = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        precondition(status == errSecSuccess, "SecRandomCopyBytes failed: \(status)")
        return base64URLEncodeNoPadding(Data(bytes))
    }

    /// Encodes `data` as URL-safe Base64 without padding (RFC 4648 §5).
    ///
    /// Standard Base64 alphabet is converted: `+` → `-`, `/` → `_`, and `=` padding is stripped.
    /// Mirrors `java.util.Base64.getUrlEncoder().withoutPadding()` used on Android.
    private static func base64URLEncodeNoPadding(_ data: Data) -> String {
        var s = data.base64EncodedString()
        s = s.replacingOccurrences(of: "+", with: "-")
        s = s.replacingOccurrences(of: "/", with: "_")
        s = s.replacingOccurrences(of: "=", with: "")
        return s
    }
}
