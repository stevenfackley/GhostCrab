import Foundation
import Observation

/// Why the live camera scanner could not start capture.
///
/// `QrScanScreen` maps VisionKit's `DataScannerViewController.ScanningUnavailable`
/// and a refused camera prompt to this, so ``QrScanViewModel`` stays
/// VisionKit-free and testable.
public enum QrScannerFailure: Error, Sendable, Equatable {
    /// The user refused camera access. They can turn it back on in Settings.
    case cameraAccessDenied
    /// A device policy (Screen Time, MDM) blocks the camera. The GhostCrab
    /// camera switch in Settings cannot lift it.
    case cameraRestricted
    /// This device cannot run the data scanner.
    case unsupported
    /// `startScanning()` threw something else. Carries only the error's type
    /// name, which can contain no payload or token.
    case startFailed(errorType: String)
}

/// ViewModel for `QrScanScreen`.
///
/// Accepts two payload shapes:
///
/// 1. **Raw URL**: `http://…` / `https://…`, validated by ``PairingLink/gatewayURL(from:)``
///    (host required, no userinfo, port in 1–65535) and used as the manual-entry prefill;
///    or `ws://…` / `wss://…` (as the gateway prints its WebSocket URL), mapped to the
///    matching `http(s)` base URL by ``PairingLink/webSocketGatewayURL(from:)``.
/// 2. **Pairing link**: `ghostcrab://pair?p=<base64url JSON>`, decoded by ``PairingLink``.
///    The gateway URL becomes the prefill. The bearer token and suggested name (if any) are stashed
///    in ``PairingHandoff`` and are never passed to navigation, because `Route`
///    is `Hashable` and lives in the navigation path / state restoration.
///
/// An `openclaw qr` setup code (bare or `oc-pair://`) is recognised only to explain it
/// (``PairingLink/setupCodeExplanation(_:)``): its bootstrap token serves node clients
/// only (ADR-0007), so nothing is stashed and nothing navigates.
///
/// Input over ``PairingLink/maxLength`` is rejected before any parsing. A scan
/// never connects: it only pre-fills the manual-entry form.
@MainActor
@Observable
public final class QrScanViewModel {

    // MARK: - State

    /// The last accepted gateway URL. Set right before ``onNavigate`` fires.
    public private(set) var lastDecoded: URL?

    /// Human-readable validation error for the last rejected payload, or why
    /// the scanner could not start (``scannerDidFail(_:)``), or `nil`.
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
    /// On success: stashes any pairing token and name, sets ``lastDecoded`` and invokes ``onNavigate``.
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
                pairingHandoff.stash(request)
                accept(request.gatewayURL)
            } catch {
                self.error = "Pairing QR code is malformed: \(error.localizedDescription)"
            }
            return
        }

        if let setupCode = PairingLink.setupCode(trimmed) {
            // Its bootstrap token serves node clients only (ADR-0007): explain, never pair with it.
            error = PairingLink.setupCodeExplanation(setupCode)
            return
        }

        guard let url = PairingLink.gatewayURL(from: trimmed) ?? PairingLink.webSocketGatewayURL(from: trimmed) else {
            error = "QR code doesn't contain a valid gateway URL"
            return
        }
        accept(url)
    }

    /// The scanner bridge could not start or restart capture. Sets ``error``
    /// so the screen says why the camera shows nothing, and offers "Scan again".
    ///
    /// - Parameter failure: Why capture did not start.
    public func scannerDidFail(_ failure: QrScannerFailure) {
        switch failure {
        case .cameraAccessDenied:
            error = "Camera access is off for GhostCrab. Allow it in Settings > Privacy & Security > Camera, or use Manual Entry."
        case .cameraRestricted:
            error = "The camera is restricted on this device (Screen Time or device management), so GhostCrab can't turn it on. Use Manual Entry instead."
        case .unsupported:
            error = "Live QR scanning is unavailable on this device. Use Manual Entry instead."
        case .startFailed(let errorType):
            error = "The camera couldn't start (\(errorType)). Tap Scan again, or use Manual Entry."
        }
    }

    /// "Scan again" after an invalid scan or a scanner failure was surfaced:
    /// clears ``error`` and starts a new ``scanGeneration`` so the scanner
    /// restarts capture.
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
