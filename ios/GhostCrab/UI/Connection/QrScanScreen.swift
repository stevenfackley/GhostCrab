import SwiftUI
#if canImport(VisionKit)
import VisionKit
#endif
#if canImport(AVFoundation)
import AVFoundation
#endif

/// QR scan flow. Mirrors `QrScanScreen.kt`.
///
/// Wraps iOS 16+'s `DataScannerViewController` in a `UIViewControllerRepresentable`
/// for live QR decoding, with a cyan viewfinder overlay drawn in SwiftUI.
///
/// On Mac Catalyst we render an empty-state fallback (no `DataScannerViewController`
/// support) with a button that pushes straight to the manual-entry screen, whose
/// Pairing link field takes a pasted `ghostcrab://pair` link or gateway URL.
public struct QrScanScreen: View {

    @Environment(\.appContainer) private var container
    @Environment(\.navigate) private var navigate
    @Environment(\.dismiss) private var dismiss

    @State private var vm: QrScanViewModel?

    public init() {}

    public var body: some View {
        ZStack {
            DesignTokens.Color.abyss.ignoresSafeArea()

            if let vm {
                content(vm: vm)
            } else {
                ProgressView().tint(DesignTokens.Color.cyanPulse)
            }
        }
        .navigationTitle("Scan QR Code")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(DesignTokens.Color.abyssRaised, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
        #endif
        .onAppear {
            if let vm {
                // Back from Manual Entry: an accepted scan stopped capture.
                vm.screenDidAppear()
                return
            }
            let new = container.makeQrScanVM()
            // URL only: a pairing token was already stashed in the container's
            // PairingHandoff and never enters the route.
            new.onNavigate = { url in
                navigate(.manualEntry(prefillURL: url))
            }
            vm = new
        }
    }

    // MARK: - Body content

    @ViewBuilder
    private func content(vm: QrScanViewModel) -> some View {
        #if targetEnvironment(macCatalyst)
        macCatalystFallback
        #else
        scannerStack(vm: vm)
        #endif
    }

    // MARK: - Scanner stack (iOS/iPadOS)

    @ViewBuilder
    private func scannerStack(vm: QrScanViewModel) -> some View {
        ZStack {
            // Same condition as the QRScannerRepresentable definition below:
            // os(iOS) alone is also true on Mac Catalyst, where it is not defined.
            #if os(iOS) && !targetEnvironment(macCatalyst)
            QRScannerRepresentable(
                scanGeneration: vm.scanGeneration,
                onPayload: { payload in
                    vm.onCodeScanned(payload)
                },
                onFailure: { failure in
                    vm.scannerDidFail(failure)
                }
            )
            .ignoresSafeArea()
            #else
            Color.black.ignoresSafeArea()
            #endif

            ViewfinderOverlay()
                .allowsHitTesting(false)

            if let message = vm.error {
                VStack {
                    Spacer()
                    GlassSurface {
                        Text(message)
                            .font(AppFont.body(14))
                            .foregroundStyle(DesignTokens.Color.crimsonError)
                            .multilineTextAlignment(.center)
                            .padding(DesignTokens.Spacing.md)
                            .frame(maxWidth: .infinity)
                    }
                    .padding(DesignTokens.Spacing.md)

                    Button("Scan again") {
                        vm.scanAgain()
                    }
                    .font(AppFont.bodyBold(14))
                    .foregroundStyle(DesignTokens.Color.cyanPrimary)
                    .padding(.bottom, DesignTokens.Spacing.lg)
                }
            } else {
                VStack {
                    Spacer()
                    Text("Align the QR code inside the frame")
                        .font(AppFont.body(13))
                        .foregroundStyle(DesignTokens.Color.textPrimary)
                        .padding(.horizontal, DesignTokens.Spacing.md)
                        .padding(.vertical, DesignTokens.Spacing.sm)
                        .background(
                            DesignTokens.Shape.small.fill(Color.black.opacity(0.55))
                        )
                        .padding(.bottom, DesignTokens.Spacing.xl)
                }
            }
        }
    }

    // MARK: - Mac Catalyst fallback

    @ViewBuilder
    private var macCatalystFallback: some View {
        EmptyState(
            icon: "qrcode.viewfinder",
            title: "QR scanning isn't supported on Mac",
            message: "Open Manual Entry and paste the gateway URL or the ghostcrab://pair link into its Pairing link field.",
            action: (
                label: "Open Manual Entry",
                run: { navigate(.manualEntry(prefillURL: nil)) }
            )
        )
    }
}

// MARK: - Viewfinder overlay

private struct ViewfinderOverlay: View {
    var body: some View {
        GeometryReader { proxy in
            let boxSide = min(proxy.size.width, proxy.size.height) * 0.65
            let armLength = boxSide * 0.12
            let strokeWidth: CGFloat = 4
            let origin = CGPoint(
                x: (proxy.size.width - boxSide) / 2,
                y: (proxy.size.height - boxSide) / 2
            )

            ZStack {
                Color.black.opacity(0.55)
                    .mask(
                        // Punch a transparent hole over the scanning box.
                        Rectangle()
                            .overlay(
                                Rectangle()
                                    .frame(width: boxSide, height: boxSide)
                                    .position(
                                        x: origin.x + boxSide / 2,
                                        y: origin.y + boxSide / 2
                                    )
                                    .blendMode(.destinationOut)
                            )
                            .compositingGroup()
                    )

                cornerArms(origin: origin, side: boxSide, arm: armLength, stroke: strokeWidth)
                    .stroke(
                        DesignTokens.Color.cyanPrimary,
                        style: StrokeStyle(lineWidth: strokeWidth, lineCap: .round)
                    )
            }
        }
    }

    private func cornerArms(origin: CGPoint, side: CGFloat, arm: CGFloat, stroke: CGFloat) -> Path {
        var path = Path()
        let left = origin.x
        let top = origin.y
        let right = origin.x + side
        let bottom = origin.y + side

        // Top-left
        path.move(to: CGPoint(x: left, y: top)); path.addLine(to: CGPoint(x: left + arm, y: top))
        path.move(to: CGPoint(x: left, y: top)); path.addLine(to: CGPoint(x: left, y: top + arm))

        // Top-right
        path.move(to: CGPoint(x: right, y: top)); path.addLine(to: CGPoint(x: right - arm, y: top))
        path.move(to: CGPoint(x: right, y: top)); path.addLine(to: CGPoint(x: right, y: top + arm))

        // Bottom-left
        path.move(to: CGPoint(x: left, y: bottom)); path.addLine(to: CGPoint(x: left + arm, y: bottom))
        path.move(to: CGPoint(x: left, y: bottom)); path.addLine(to: CGPoint(x: left, y: bottom - arm))

        // Bottom-right
        path.move(to: CGPoint(x: right, y: bottom)); path.addLine(to: CGPoint(x: right - arm, y: bottom))
        path.move(to: CGPoint(x: right, y: bottom)); path.addLine(to: CGPoint(x: right, y: bottom - arm))

        return path
    }
}

// MARK: - DataScannerViewController bridge

#if os(iOS) && !targetEnvironment(macCatalyst)
/// SwiftUI bridge around `VisionKit.DataScannerViewController`.
///
/// Falls back to a black background + status text if the device cannot host a
/// data scanner (older hardware, missing VisionKit, etc.). Camera access is
/// requested before capture starts, so the first scan shows the system prompt;
/// a refusal surfaces as ``QrScannerFailure/cameraAccessDenied`` and a device
/// restriction as ``QrScannerFailure/cameraRestricted``, instead of "unavailable".
private struct QRScannerRepresentable: UIViewControllerRepresentable {

    /// ``QrScanViewModel/scanGeneration``; a change restarts capture.
    let scanGeneration: Int
    let onPayload: (String) -> Void
    /// Capture could not start (camera refused, or VisionKit threw).
    let onFailure: (QrScannerFailure) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(scanGeneration: scanGeneration, onPayload: onPayload, onFailure: onFailure)
    }

    func makeUIViewController(context: Context) -> UIViewController {
        // Deployment target is iOS 18+, so `DataScannerViewController` is always
        // present. Hardware support is still a real gate (e.g. devices without
        // the Neural Engine return `false` from `isSupported`). Do not gate on
        // `isAvailable` here: it is also `false` until camera access is granted,
        // so on first use it showed "unavailable" and never prompted.
        guard DataScannerViewController.isSupported else {
            return UnsupportedScannerVC()
        }

        let scanner = DataScannerViewController(
            recognizedDataTypes: [.barcode(symbologies: [.qr])],
            qualityLevel: .balanced,
            recognizesMultipleItems: false,
            isHighFrameRateTrackingEnabled: false,
            isPinchToZoomEnabled: true,
            isGuidanceEnabled: false,
            isHighlightingEnabled: false
        )
        scanner.delegate = context.coordinator
        context.coordinator.scanner = scanner

        // Ask for camera access first (the system prompt on first use; an
        // immediate answer afterwards), then check `isAvailable`, which needs
        // that access. Construction is not blocked. A main-actor Task keeps the
        // work on the main actor. It holds the coordinator weakly (and the
        // coordinator holds the scanner weakly), so leaving the screen while
        // the prompt is up frees both.
        let coordinator = context.coordinator
        Task { @MainActor [weak coordinator] in
            let granted = await AVCaptureDevice.requestAccess(for: .video)
            guard let coordinator else { return }
            guard granted else {
                // Refused by the user, or restricted by a device policy (no
                // prompt is shown then): different fixes, different copy.
                coordinator.report(.cameraBlocked(status: AVCaptureDevice.authorizationStatus(for: .video)))
                return
            }
            // With access granted, `isAvailable` is `false` only when a device
            // policy (Screen Time, MDM) restricts the camera.
            guard DataScannerViewController.isAvailable else {
                coordinator.report(.cameraRestricted)
                return
            }
            coordinator.startScanning()
        }
        return scanner
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        context.coordinator.restartIfNeeded(scanGeneration: scanGeneration)
    }

    @MainActor
    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        let onPayload: (String) -> Void
        let onFailure: (QrScannerFailure) -> Void
        weak var scanner: DataScannerViewController?
        private var didEmit = false
        private var scanGeneration: Int

        init(
            scanGeneration: Int,
            onPayload: @escaping (String) -> Void,
            onFailure: @escaping (QrScannerFailure) -> Void
        ) {
            self.scanGeneration = scanGeneration
            self.onPayload = onPayload
            self.onFailure = onFailure
        }

        /// Starts capture unless it is already running. A VisionKit refusal
        /// goes to ``report(_:)`` instead of leaving a frozen camera with no
        /// message.
        func startScanning() {
            guard let scanner, !scanner.isScanning else { return }
            do {
                try scanner.startScanning()
            } catch {
                report(QrScannerFailure(scannerError: error))
            }
        }

        /// Hands `failure` to the VM on the next main-actor turn:
        /// ``restartIfNeeded(scanGeneration:)`` runs inside a SwiftUI view
        /// update, where writing observable state is not allowed.
        func report(_ failure: QrScannerFailure) {
            Task { @MainActor [weak self] in
                self?.onFailure(failure)
            }
        }

        /// Re-arms the scanner when the VM starts a new scan generation ("Scan
        /// again", or the screen reappearing). Emitting a payload stops
        /// capture, so without this the camera would stay frozen.
        func restartIfNeeded(scanGeneration newGeneration: Int) {
            guard newGeneration != scanGeneration else { return }
            scanGeneration = newGeneration
            didEmit = false
            startScanning()
        }

        func dataScanner(
            _ dataScanner: DataScannerViewController,
            didAdd addedItems: [RecognizedItem],
            allItems: [RecognizedItem]
        ) {
            guard !didEmit else { return }
            for item in addedItems {
                if case .barcode(let barcode) = item, let value = barcode.payloadStringValue {
                    didEmit = true
                    onPayload(value)
                    dataScanner.stopScanning()
                    return
                }
            }
        }

        /// The scanner stopped mid-session (for example camera access was
        /// revoked). Same visible error as a failed start.
        func dataScanner(
            _ dataScanner: DataScannerViewController,
            becameUnavailableWithError error: DataScannerViewController.ScanningUnavailable
        ) {
            report(QrScannerFailure(scannerError: error))
        }
    }
}

private extension QrScannerFailure {
    /// Maps what `DataScannerViewController.startScanning()` throws. Keeps
    /// only the error's type name for anything unexpected.
    init(scannerError error: any Error) {
        switch error as? DataScannerViewController.ScanningUnavailable {
        case .cameraRestricted?:
            // VisionKit uses one case for a refusal and a device policy.
            self = .cameraBlocked(status: AVCaptureDevice.authorizationStatus(for: .video))
        case .unsupported?:
            self = .unsupported
        default:
            self = .startFailed(errorType: String(describing: type(of: error)))
        }
    }
}

extension QrScannerFailure {
    /// Camera use is not available to the scanner. `.denied` is the user's
    /// choice, which Settings can undo. `.restricted` is a device policy
    /// (Screen Time, MDM) the user may not be able to lift, and so is no
    /// camera despite `.authorized`, which is how VisionKit reports such a
    /// policy. Internal so tests can pin the mapping.
    ///
    /// - Parameter status: `AVCaptureDevice.authorizationStatus(for: .video)`.
    /// - Returns: The failure whose copy names the right fix.
    static func cameraBlocked(status: AVAuthorizationStatus) -> QrScannerFailure {
        switch status {
        case .restricted, .authorized:
            return .cameraRestricted
        case .denied, .notDetermined:
            return .cameraAccessDenied
        @unknown default:
            return .cameraAccessDenied
        }
    }
}

/// Fallback view controller shown on hardware that cannot host
/// `DataScannerViewController`.
private final class UnsupportedScannerVC: UIViewController {
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        let label = UILabel()
        label.text = "Live QR scanning is unavailable on this device.\nUse Manual Entry instead."
        label.numberOfLines = 0
        label.textAlignment = .center
        label.textColor = .white
        label.font = .systemFont(ofSize: 15)
        label.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(label)
        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            label.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            label.leadingAnchor.constraint(greaterThanOrEqualTo: view.leadingAnchor, constant: 24),
            label.trailingAnchor.constraint(lessThanOrEqualTo: view.trailingAnchor, constant: -24),
        ])
    }
}
#endif
