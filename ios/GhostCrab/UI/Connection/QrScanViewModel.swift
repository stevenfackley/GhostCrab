import Foundation
import Observation

/// ViewModel for `QrScanScreen`.
///
/// Validates payloads decoded by the QR scanner and (on success) hands the caller
/// a URL string that can be used to prefill the manual-entry form.
///
/// Two payload shapes are accepted:
///
/// 1. **Raw URL** — `http://...` or `https://...`. Used directly as the prefill.
/// 2. **Deep link** — `ghostcrab://pair?p=<base64url-encoded JSON>` where the JSON
///    object has shape `{"url": "...", "token": "...", "displayName": "..."}`. The
///    `url` field becomes the prefill; `token` and `displayName` are surfaced via
///    the decoded ``PairingPayload`` for any caller that needs them.
@MainActor
@Observable
public final class QrScanViewModel {

    // MARK: - State

    /// The last successfully validated payload. Set right before `onNavigate` fires.
    public private(set) var lastDecoded: String?

    /// Human-readable validation error for the last rejected QR payload, or `nil`.
    public private(set) var error: String?

    /// Decoded `ghostcrab://pair` payload, if the most recent valid scan was a deep link.
    public private(set) var pairingPayload: PairingPayload?

    // MARK: - Side-effect callback

    /// Invoked with the URL string when a scan is accepted. The screen wires this to
    /// `navigate(.manualEntry(prefillURL: URL(string: ...)))`.
    ///
    /// Always read/written from the main actor (the screen sets it on `.onAppear`,
    /// the VM calls it from `onCodeScanned`).
    public var onNavigate: (@MainActor (String, PairingPayload?) -> Void)?

    // MARK: - Init

    public init() {}

    // MARK: - Public API

    /// Validates a payload decoded by the scanner.
    ///
    /// On success: sets ``lastDecoded`` and invokes ``onNavigate``.
    /// On failure: sets ``error`` so the screen can surface it.
    ///
    /// - Parameter payload: Raw QR-decoded string.
    public func onCodeScanned(_ payload: String) {
        let trimmed = payload.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            error = "QR code is empty"
            return
        }

        if trimmed.lowercased().hasPrefix("ghostcrab://pair") {
            do {
                let decoded = try Self.decodePairingDeepLink(trimmed)
                pairingPayload = decoded
                accept(url: decoded.url)
            } catch {
                self.error = "Pairing QR code is malformed: \(error.localizedDescription)"
            }
            return
        }

        let lower = trimmed.lowercased()
        if lower.hasPrefix("http://") || lower.hasPrefix("https://") {
            // Validate the URL parses at all.
            guard URL(string: trimmed) != nil else {
                error = "QR code doesn't contain a valid gateway URL"
                return
            }
            pairingPayload = nil
            accept(url: trimmed)
            return
        }

        error = "QR code doesn't contain a valid gateway URL"
    }

    /// Re-enables the scanner after an invalid scan was surfaced.
    public func clearError() {
        error = nil
    }

    // MARK: - Internals

    private func accept(url: String) {
        error = nil
        lastDecoded = url
        onNavigate?(url, pairingPayload)
    }

    // MARK: - Deep-link decode

    /// Decodes a `ghostcrab://pair?p=<base64url>` URL into a ``PairingPayload``.
    ///
    /// Direct port of the Android `QrScanViewModel` deep-link path: the `p` query
    /// parameter is base64url-encoded JSON shaped like
    /// `{"url": "...", "token": "...", "displayName": "..."}`. Only `url` is required.
    private static func decodePairingDeepLink(_ raw: String) throws -> PairingPayload {
        guard let comps = URLComponents(string: raw),
              let pParam = comps.queryItems?.first(where: { $0.name == "p" })?.value,
              !pParam.isEmpty
        else {
            throw PairingDecodeError.missingParameter
        }

        guard let jsonData = Data(base64URLEncoded: pParam) else {
            throw PairingDecodeError.invalidBase64
        }

        let decoded = try JSONDecoder().decode(PairingPayload.self, from: jsonData)
        guard URL(string: decoded.url) != nil,
              decoded.url.lowercased().hasPrefix("http://") || decoded.url.lowercased().hasPrefix("https://")
        else {
            throw PairingDecodeError.invalidURL
        }
        return decoded
    }
}

// MARK: - Pairing payload

/// JSON shape carried by `ghostcrab://pair?p=...` deep links.
public struct PairingPayload: Codable, Sendable, Equatable {
    public let url: String
    public let token: String?
    public let displayName: String?
}

/// Internal decode errors for the deep-link path.
private enum PairingDecodeError: LocalizedError {
    case missingParameter
    case invalidBase64
    case invalidURL

    var errorDescription: String? {
        switch self {
        case .missingParameter: return "missing `p` parameter"
        case .invalidBase64:    return "base64 decode failed"
        case .invalidURL:       return "URL field is not http(s)"
        }
    }
}

// MARK: - base64url helper

private extension Data {
    /// Decodes a base64url-encoded string (RFC 4648 §5) — `-`/`_` instead of `+`/`/`
    /// and optional padding.
    init?(base64URLEncoded: String) {
        var s = base64URLEncoded
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let mod = s.count % 4
        if mod > 0 {
            s.append(String(repeating: "=", count: 4 - mod))
        }
        guard let data = Data(base64Encoded: s) else { return nil }
        self = data
    }
}
