import Foundation

/// A decoded `ghostcrab://pair` request.
///
/// `description`, `debugDescription` and the reflection mirror redact ``token``,
/// so a stray `print`, `dump` or string interpolation never leaks the bearer.
public struct PairingRequest: Sendable, Equatable {
    /// Gateway base URL: `http` or `https`, a host, an explicit port, no userinfo,
    /// no path, query or fragment.
    public let gatewayURL: URL
    /// Bearer token to pre-fill, or `nil` when the link carries none (or a blank one).
    /// Sensitive: never log it.
    public let token: String?
    /// Optional human label suggested by the gateway's pairing page (`name` on the wire).
    public let displayName: String?

    /// - Parameters:
    ///   - gatewayURL: Validated gateway base URL.
    ///   - token: Bearer token, or `nil`.
    ///   - displayName: Optional label.
    public init(gatewayURL: URL, token: String?, displayName: String?) {
        self.gatewayURL = gatewayURL
        self.token = token
        self.displayName = displayName
    }
}

extension PairingRequest: CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    /// Redacted form: the token is replaced by a placeholder.
    public var description: String {
        let tokenText = token == nil ? "nil" : "<redacted>"
        return "PairingRequest(gatewayURL: \(gatewayURL.absoluteString), token: \(tokenText), displayName: \(displayName ?? "nil"))"
    }

    /// Same as ``description``.
    public var debugDescription: String { description }

    /// Mirror without the token, so `dump` stays redacted.
    public var customMirror: Mirror {
        Mirror(
            self,
            children: [
                "gatewayURL": gatewayURL.absoluteString,
                "token": token == nil ? "nil" : "<redacted>",
                "displayName": displayName ?? "nil",
            ],
            displayStyle: .struct
        )
    }
}

/// Why a pairing link was rejected. Descriptions never include the payload.
public enum PairingLinkError: Error, Equatable, Sendable {
    /// The raw link is longer than ``PairingLink/maxLength``. Checked before any decoding.
    case payloadTooLarge
    /// Scheme is not `ghostcrab` or host is not `pair`.
    case notAPairingLink
    /// No non-empty `p` query parameter.
    case missingPayload
    /// `p` is not valid base64url.
    case invalidBase64
    /// `p` decodes, but not to a JSON object with an integer `v` and string fields.
    case invalidJSON
    /// `v` is not ``PairingLink/supportedVersion``.
    case unsupportedVersion(Int)
    /// `url` is missing, or is not an `http`/`https` URL with a host, no userinfo
    /// and a port in 1–65535.
    case invalidGatewayURL
}

extension PairingLinkError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .payloadTooLarge:
            return "pairing data is too large (over \(PairingLink.maxLength) characters)"
        case .notAPairingLink:
            return "not a ghostcrab://pair link"
        case .missingPayload:
            return "missing `p` parameter"
        case .invalidBase64:
            return "base64 decode failed"
        case .invalidJSON:
            return "payload is not the expected JSON object"
        case .unsupportedVersion(let version):
            return "unsupported pairing data version \(version)"
        case .invalidGatewayURL:
            return "no valid http(s) gateway URL"
        }
    }
}

/// Pure decoder for `ghostcrab://pair?p=<base64url JSON>` links.
///
/// Payload JSON (schema v1, the shape the gateway pairing page and the Android
/// client use): `{"v": 1, "url": "<http(s) gateway URL>", "token": "<optional
/// bearer>", "name": "<optional>"}`. Unknown keys are ignored.
///
/// Checks, in order: length cap (before any parsing or decoding), scheme and
/// host, `p` present, base64url, the version field on its own, then the v1
/// fields and the gateway URL. Used by the QR scanner and by the app's
/// `onOpenURL` handler.
public enum PairingLink {

    /// URL scheme registered in `project.yml` (`CFBundleURLTypes`).
    public static let scheme = "ghostcrab"

    /// Host that marks a pairing request.
    public static let host = "pair"

    /// Longest link (in UTF-8 bytes) ``decode(_:)`` accepts; longer input is
    /// rejected before any decoding. Matches Android's `MAX_PAIR_CODE_LENGTH`.
    public static let maxLength = 4096

    /// The only payload schema version this build understands.
    public static let supportedVersion = 1

    /// Default port for an `http` gateway URL without one (OpenClaw's default).
    public static let defaultHTTPPort = 18789

    /// Default port for an `https` gateway URL without one (tunnels, reverse proxies).
    public static let defaultHTTPSPort = 443

    /// Whether `raw` uses the `ghostcrab:` scheme, ignoring case and surrounding
    /// whitespace. Every `ghostcrab:` link counts as pairing data, so a broken one
    /// gets a pairing error rather than the generic "not a gateway URL" message.
    /// The host and payload are not validated.
    ///
    /// - Parameter raw: Candidate link string.
    /// - Returns: `true` for `ghostcrab:…` input.
    public static func isPairingLink(_ raw: String) -> Bool {
        let marker = scheme + ":"
        let candidate = raw.drop(while: { $0.isWhitespace || $0.isNewline })
        return candidate.prefix(marker.count).lowercased() == marker
    }

    /// Decodes and validates a pairing link.
    ///
    /// - Parameter raw: The full link, for example a QR payload or an `onOpenURL` URL's `absoluteString`.
    /// - Returns: The validated ``PairingRequest``. A blank token is normalised to `nil`.
    /// - Throws: ``PairingLinkError`` naming the first check that failed.
    public static func decode(_ raw: String) throws -> PairingRequest {
        // Size first: nothing below runs on oversized input.
        guard raw.utf8.count <= maxLength else {
            throw PairingLinkError.payloadTooLarge
        }
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let comps = URLComponents(string: trimmed),
              comps.scheme?.lowercased() == scheme,
              comps.host?.lowercased() == host
        else {
            throw PairingLinkError.notAPairingLink
        }
        guard let payload = comps.queryItems?.first(where: { $0.name == "p" })?.value,
              !payload.isEmpty
        else {
            throw PairingLinkError.missingPayload
        }
        guard let json = base64URLDecode(payload) else {
            throw PairingLinkError.invalidBase64
        }

        // Version on its own first, so a newer schema is reported as such even if
        // the rest of its shape differs.
        let decoder = JSONDecoder()
        let header: Header
        do {
            header = try decoder.decode(Header.self, from: json)
        } catch {
            throw PairingLinkError.invalidJSON
        }
        guard header.v == supportedVersion else {
            throw PairingLinkError.unsupportedVersion(header.v)
        }
        let wire: WireV1
        do {
            wire = try decoder.decode(WireV1.self, from: json)
        } catch {
            throw PairingLinkError.invalidJSON
        }

        guard let urlString = wire.url, let validatedURL = Self.gatewayURL(from: urlString) else {
            throw PairingLinkError.invalidGatewayURL
        }
        let token = wire.token?.trimmingCharacters(in: .whitespacesAndNewlines)
        return PairingRequest(
            gatewayURL: validatedURL,
            token: (token?.isEmpty ?? true) ? nil : token,
            displayName: wire.name
        )
    }

    /// Validates and normalises a gateway base URL from a pairing payload or a
    /// plain-URL QR code. Mirrors Android's `GatewayUrl.parse`.
    ///
    /// - Parameter raw: Untrusted URL text.
    /// - Returns: `scheme://host:port` with an explicit port (``defaultHTTPPort`` /
    ///   ``defaultHTTPSPort`` when absent) and no path, query or fragment; or `nil`
    ///   unless `raw` is an `http`/`https` URL with a non-empty host, no userinfo
    ///   and a port in 1–65535. Oversized input (over ``maxLength``) is `nil`.
    ///   An IPv6 zone id is dropped (`http://[fe80::1%en0]:18789` →
    ///   `http://[fe80::1]:18789`), like Android's `GatewayUrl`.
    public static func gatewayURL(from raw: String) -> URL? {
        guard raw.utf8.count <= maxLength else { return nil }
        let trimmed = strippingIPv6ZoneID(raw.trimmingCharacters(in: .whitespacesAndNewlines))
        guard let comps = URLComponents(string: trimmed),
              let urlScheme = comps.scheme?.lowercased(),
              urlScheme == "http" || urlScheme == "https",
              comps.user == nil, comps.password == nil,
              let urlHost = comps.host, !urlHost.isEmpty
        else {
            return nil
        }
        let port = comps.port ?? (urlScheme == "https" ? defaultHTTPSPort : defaultHTTPPort)
        guard (1...65535).contains(port) else { return nil }

        var normalised = comps
        normalised.scheme = urlScheme
        normalised.port = port
        normalised.path = ""
        normalised.query = nil
        normalised.fragment = nil
        return normalised.url
    }

    // MARK: - Internals

    /// Drops the zone id (`%en0`, or RFC 6874's encoded `%25en0`) from a
    /// bracketed IPv6 host right after `://`. A zone names an interface on the
    /// machine that made the link, is meaningless here, and is not valid in a
    /// URL authority. Anything else is returned unchanged.
    private static func strippingIPv6ZoneID(_ url: String) -> String {
        guard let open = url.range(of: "://["),
              let close = url[open.upperBound...].firstIndex(of: "]"),
              let percent = url[open.upperBound..<close].firstIndex(of: "%")
        else {
            return url
        }
        return String(url[..<percent]) + String(url[close...])
    }

    /// Just the schema version.
    private struct Header: Decodable {
        let v: Int
    }

    /// Schema v1. Every field is optional on the wire; `url` is checked after decoding.
    private struct WireV1: Decodable, CustomStringConvertible {
        let url: String?
        let token: String?
        let name: String?

        /// Redacts `token`: the payload is never logged, but keep a stray print from leaking it.
        var description: String {
            "WireV1(url: \(url ?? "nil"), token: \(token == nil ? "nil" : "<redacted>"), name: \(name ?? "nil"))"
        }
    }

    /// RFC 4648 §5 base64url (with `-`/`_`, optional padding) to raw bytes.
    private static func base64URLDecode(_ value: String) -> Data? {
        var base64 = value
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let remainder = base64.count % 4
        if remainder > 0 {
            base64.append(String(repeating: "=", count: 4 - remainder))
        }
        return Data(base64Encoded: base64)
    }
}
