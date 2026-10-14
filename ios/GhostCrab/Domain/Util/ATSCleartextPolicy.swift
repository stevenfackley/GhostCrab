import Foundation

/// Predicts whether iOS App Transport Security refuses a plain-HTTP load to
/// `host` before any app code, ``CleartextPublicIPGuard`` included, runs.
///
/// ATS exempts IP literals, unqualified host names (no dot) and `.local`
/// names. Every other name needs HTTPS: public FQDNs, Tailscale MagicDNS
/// `*.ts.net`, split-DNS and `home.arpa` names. The Settings cleartext toggle
/// cannot change this. See `docs/adr/0006-ios-ats-local-networking.md`.
public enum ATSCleartextPolicy {

    /// - Parameter rawHost: Host as typed (case, surrounding whitespace and IPv6 brackets are tolerated).
    /// - Returns: `true` if `http://<host>` will fail with `NSURLErrorAppTransportSecurityRequiresSecureConnection`.
    public static func blocksCleartext(host rawHost: String) -> Bool {
        var host = rawHost.trimmingCharacters(in: .whitespaces).lowercased()
        if host.hasPrefix("[") && host.hasSuffix("]") {
            host = String(host.dropFirst().dropLast())
        }
        if host.hasSuffix(".") {
            host = String(host.dropLast())
        }
        guard !host.isEmpty else { return false }
        if host.contains(":") { return false }                                          // IPv6 literal
        if host.allSatisfy({ $0.isASCII && ($0.isNumber || $0 == ".") }) { return false } // IPv4 literal
        if !host.contains(".") { return false }                                         // unqualified name
        if host.hasSuffix(".local") { return false }                                    // mDNS / Bonjour
        return true
    }
}
