import Foundation
import Observation

/// ViewModel for `QrScanScreen`.
///
/// Accepts two payload shapes:
///
/// 1. **Raw URL**: `http://…` / `https://…`, validated by ``PairingLink/gatewayURL(from:)``
///    (host required, no userinfo, port in 1–65535) and used as the manual-entry prefill.
/// 2. **Pairing link**: `ghostcrab://pair?p=<base64url JSON>`, decoded by ``PairingLink``.
///    The gateway URL becomes the prefill. The bearer token (if any) is stashed
///    in ``PairingHandoff`` and is never passed to navigation, because `Route`
///    is `Hashable` and lives in `NavigationPath` / state restoration.
///
/// Input over ``PairingLink/maxLength`` is rejected before any parsing. A scan
/// never connects: it only pre-fills the manual-entry form.
@MainActor
@Observable
public final class QrScanViewModel {

    // MARK: - State

    /// The last accepted gateway URL. Set right before ``onNavigate`` fires.
    public private(set) var lastDecoded: URL?

    /// Human-readable validation error for the last rejected payload, or `nil`.
    /// Never echoes the payload (a pairing payload contains the token).
    public private(set) var error: String?

    /// Bumped by ``scanAgain()`` and ``screenDidAppear()``. The scanner bridge
    /// stops capture after it emits one payload and restarts it whenever this
    /// value changes, so "Scan again" after a rejected code, or coming back
    /// from Manual Entry, gets a live camera again.
    public private(set) var scanGeneration: Int = 0

    // MARK: - Side-effect callback

    /// Invoked with the gateway URL when a scan is accepted. Carries no secrets.
    /// The screen wires it to `navigate(.manualEntry(prefillURL:))`.
    public var onNavigate: (@MainActor (URL) -> Void)?

    // MARK: - Dependencies

    private let pairingHandoff: PairingHandoff

    // MARK: - Init

    /// - Parameter pairingHandoff: One-shot token hand-off shared with ``ManualEntryViewModel``.
    public init(pairingHandoff: PairingHandoff) {
        self.pairingHandoff = pairingHandoff
    }

    // MARK: - Public API

    /// Validates a payload decoded by the scanner.
    ///
    /// On success: stashes any pairing token, sets ``lastDecoded`` and invokes ``onNavigate``.
    /// On failure: sets ``error`` so the screen can surface it.
    ///
    /// - Parameter payload: Raw QR-decoded string.
    public func onCodeScanned(_ payload: String) {
        guard payload.utf8.count <= PairingLink.maxLength else {
            error = "QR code is too large (over \(PairingLink.maxLength) characters)"
            return
        }
        let trimmed = payload.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            error = "QR code is empty"
            return
        }

        if PairingLink.isPairingLink(trimmed) {
            do {
                let request = try PairingLink.decode(trimmed)
                if let token = request.token {
                    pairingHandoff.stash(token: token, for: request.gatewayURL)
                }
                accept(request.gatewayURL)
            } catch {
                self.error = "Pairing QR code is malformed: \(error.localizedDescription)"
            }
            return
        }

        guard let url = PairingLink.gatewayURL(from: trimmed) else {
            error = "QR code doesn't contain a valid gateway URL"
            return
        }
        accept(url)
    }

    /// "Scan again" after an invalid scan was surfaced: clears ``error`` and
    /// starts a new ``scanGeneration`` so the scanner restarts capture.
    public func scanAgain() {
        error = nil
        scanGeneration += 1
    }

    /// The screen appeared (first push, or back from Manual Entry after an
    /// accepted scan stopped capture). Starts a new ``scanGeneration``.
    public func screenDidAppear() {
        scanGeneration += 1
    }

    // MARK: - Internals

    private func accept(_ url: URL) {
        error = nil
        lastDecoded = url
        onNavigate?(url)
    }
}
