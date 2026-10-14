import Foundation

/// `URLProtocol` subclass that blocks cleartext HTTP requests to public (non-LAN)
/// IP addresses unless the sending client opted in.
///
/// The opt-in travels on each request as a `URLProtocol` property
/// (``allowPropertyKey``), set by `OpenClawAPIClient.send` via
/// ``tagged(_:allowCleartextPublicIPs:)``. There is no process-global
/// switch, so two clients with different settings never interfere. A request
/// without the property is treated as "not allowed" (fail-safe).
///
/// Only numeric IPv4/IPv6 literals are evaluated. Hostname-based URLs pass
/// through (DNS-resolved addresses aren't checked here); cleartext to qualified
/// hostnames is refused earlier by iOS ATS (``ATSCleartextPolicy``,
/// `docs/adr/0006-ios-ats-local-networking.md`).
///
/// Private ranges allowed by default: RFC-1918 (10/8, 172.16/12, 192.168/16),
/// link-local (169.254/16), and loopback (127/8). IPv6 loopback (`::1`),
/// link-local (`fe80::/10`) and unique-local (`fc00::/7`) all pass through.
///
/// Direct port of `CleartextPublicIpInterceptor.kt`, whose opt-in is likewise
/// bound to one client (its OkHttp engine), not to the process.
public final class CleartextPublicIPGuard: URLProtocol, @unchecked Sendable {

    // MARK: - Per-request opt-in

    /// `URLProtocol` property key carrying the sending client's opt-in (`Bool`).
    static let allowPropertyKey = "com.qavren.ghostcrab.allowCleartextPublicIPs"

    /// Returns a copy of `request` tagged with the client's cleartext opt-in.
    ///
    /// - Parameters:
    ///   - request: Outgoing request.
    ///   - allowCleartextPublicIPs: The client's setting.
    /// - Returns: The tagged request. If the copy cannot be made mutable, the
    ///   untagged request is returned, which the guard treats as "not allowed".
    static func tagged(_ request: URLRequest, allowCleartextPublicIPs: Bool) -> URLRequest {
        guard let mutable = (request as NSURLRequest).mutableCopy() as? NSMutableURLRequest else {
            return request
        }
        URLProtocol.setProperty(allowCleartextPublicIPs, forKey: allowPropertyKey, in: mutable)
        return mutable as URLRequest
    }

    /// Whether `request` carries a client opt-in for cleartext to public IPs.
    /// Missing or non-Bool values read as `false`.
    static func isAllowed(_ request: URLRequest) -> Bool {
        (URLProtocol.property(forKey: allowPropertyKey, in: request) as? Bool) ?? false
    }

    // MARK: - URLProtocol

    public override class func canInit(with request: URLRequest) -> Bool {
        guard let url = request.url else { return false }
        guard url.scheme?.lowercased() == "http" else { return false }
        guard let host = url.host else { return false }
        // Only intercept (and reject) if this is a public IP literal AND the
        // sending client did not opt in.
        return isPublicIPLiteral(host) && !isAllowed(request)
    }

    public override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    public override func startLoading() {
        let host = request.url?.host ?? "<unknown>"
        let error = NSError(
            domain: "com.qavren.ghostcrab.CleartextPublicIPGuard",
            code: NSURLErrorAppTransportSecurityRequiresSecureConnection,
            userInfo: [
                NSLocalizedDescriptionKey:
                    "Cleartext HTTP to public IP \(host) is blocked. " +
                    "Enable it in Settings → Security or use HTTPS."
            ]
        )
        client?.urlProtocol(self, didFailWithError: error)
    }

    public override func stopLoading() { /* no-op */ }

    // MARK: - IP literal detection

    /// Returns `true` if `host` is a numeric IPv4/IPv6 literal pointing to a public address.
    /// Hostnames and private/loopback IPs return `false`.
    static func isPublicIPLiteral(_ host: String) -> Bool {
        // Strip IPv6 brackets if present (URL.host can include them on some platforms).
        let raw: String
        if host.hasPrefix("[") && host.hasSuffix("]") {
            raw = String(host.dropFirst().dropLast())
        } else {
            raw = host
        }

        if isIPv4Literal(raw) {
            guard let octets = parseIPv4Octets(raw) else { return false }
            return !isPrivateIPv4(octets)
        }

        if raw.contains(":"), let bytes = parseIPv6(raw) {
            return !isPrivateIPv6(bytes)
        }

        return false
    }

    // MARK: - IPv4

    private static let ipv4Regex: NSRegularExpression = {
        // swiftlint:disable:next force_try
        try! NSRegularExpression(pattern: #"^\d{1,3}(\.\d{1,3}){3}$"#)
    }()

    private static func isIPv4Literal(_ s: String) -> Bool {
        let range = NSRange(s.startIndex..., in: s)
        return ipv4Regex.firstMatch(in: s, range: range) != nil
    }

    private static func parseIPv4Octets(_ s: String) -> [UInt8]? {
        let parts = s.split(separator: ".")
        guard parts.count == 4 else { return nil }
        var out = [UInt8]()
        out.reserveCapacity(4)
        for p in parts {
            guard let v = Int(p), (0...255).contains(v) else { return nil }
            out.append(UInt8(v))
        }
        return out
    }

    /// RFC-1918 private + loopback + link-local + 0.0.0.0/8.
    private static func isPrivateIPv4(_ o: [UInt8]) -> Bool {
        guard o.count == 4 else { return false }
        // 0.0.0.0/8 — "this network"
        if o[0] == 0 { return true }
        // 127/8 — loopback
        if o[0] == 127 { return true }
        // 10/8
        if o[0] == 10 { return true }
        // 172.16/12
        if o[0] == 172, (16...31).contains(o[1]) { return true }
        // 192.168/16
        if o[0] == 192, o[1] == 168 { return true }
        // 169.254/16 — link-local
        if o[0] == 169, o[1] == 254 { return true }
        return false
    }

    // MARK: - IPv6

    /// Parses an IPv6 literal into 16 raw bytes via `inet_pton`. Returns `nil` if invalid.
    private static func parseIPv6(_ s: String) -> [UInt8]? {
        var buf = [UInt8](repeating: 0, count: 16)
        let ok = s.withCString { cstr in
            buf.withUnsafeMutableBytes { raw in
                inet_pton(AF_INET6, cstr, raw.baseAddress) == 1
            }
        }
        return ok ? buf : nil
    }

    /// Loopback (`::1`), unspecified (`::`), link-local (`fe80::/10`),
    /// unique-local (`fc00::/7`), or IPv4-mapped private addresses.
    private static func isPrivateIPv6(_ b: [UInt8]) -> Bool {
        guard b.count == 16 else { return false }

        // :: (all zeros) — unspecified
        if b.allSatisfy({ $0 == 0 }) { return true }

        // ::1 — loopback
        if b[0..<15].allSatisfy({ $0 == 0 }) && b[15] == 1 { return true }

        // fe80::/10 — link-local
        if b[0] == 0xFE && (b[1] & 0xC0) == 0x80 { return true }

        // fc00::/7 — unique-local
        if (b[0] & 0xFE) == 0xFC { return true }

        // IPv4-mapped (::ffff:0:0/96) — check the embedded IPv4
        let prefixZero = b[0..<10].allSatisfy { $0 == 0 }
        if prefixZero && b[10] == 0xFF && b[11] == 0xFF {
            let mapped: [UInt8] = [b[12], b[13], b[14], b[15]]
            return isPrivateIPv4(mapped)
        }

        return false
    }
}
