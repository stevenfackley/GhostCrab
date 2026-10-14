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
/// support) with a button that pushes straight to the manual-entry screen.
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
            #if os(iOS)
            QRScannerRepresentable(
                scanGeneration: vm.scanGeneration,
                onPayload: { payload in
                    vm.onCodeScanned(payload)
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
            message: "Use Manual Entry to paste the gateway URL or a ghostcrab://pair link instead.",
            action: (
                label: "Paste URL instead",
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
/// data scanner (older hardware, missing VisionKit, etc.). Camera permission is
/// resolved by VisionKit itself once `startScanning()` is called — we just
/// proactively request access so the first prompt isn't deferred.
private struct QRScannerRepresentable: UIViewControllerRepresentable {

    /// ``QrScanViewModel/scanGeneration``; a change restarts capture.
    let scanGeneration: Int
    let onPayload: (String) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(scanGeneration: scanGeneration, onPayload: onPayload) }

    func makeUIViewController(context: Context) -> UIViewController {
        // Deployment target is iOS 18+, so `DataScannerViewController` is always
        // present. Hardware/runtime support is still a real gate (e.g. devices
        // without the Neural Engine return `false` from `isSupported`).
        guard DataScannerViewController.isSupported, DataScannerViewController.isAvailable else {
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

        // Camera permission can be requested up-front. VisionKit will refuse to
        // start otherwise; we forward the decision but don't block construction.
        // A main-actor Task keeps the non-Sendable scanner on the main actor
        // (the old completion-handler + DispatchQueue hop sent it across).
        Task { @MainActor in
            guard await AVCaptureDevice.requestAccess(for: .video) else { return }
            try? scanner.startScanning()
        }
        return scanner
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        context.coordinator.restartIfNeeded(scanGeneration: scanGeneration)
    }

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        let onPayload: (String) -> Void
        weak var scanner: DataScannerViewController?
        private var didEmit = false
        private var scanGeneration: Int

        init(scanGeneration: Int, onPayload: @escaping (String) -> Void) {
            self.scanGeneration = scanGeneration
            self.onPayload = onPayload
        }

        /// Re-arms the scanner when the VM starts a new scan generation ("Scan
        /// again", or the screen reappearing). Emitting a payload stops
        /// capture, so without this the camera would stay frozen.
        @MainActor
        func restartIfNeeded(scanGeneration newGeneration: Int) {
            guard newGeneration != scanGeneration else { return }
            scanGeneration = newGeneration
            didEmit = false
            guard let scanner, !scanner.isScanning else { return }
            try? scanner.startScanning()
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
