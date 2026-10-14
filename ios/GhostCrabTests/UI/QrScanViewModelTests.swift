import Testing
import Foundation
#if canImport(AVFoundation)
import AVFoundation
#endif
@testable import GhostCrab

/// Collects the URLs a ``QrScanViewModel`` navigates with.
@MainActor
final class NavigationRecorder {
    var urls: [URL] = []
}

@MainActor
@Suite("QrScanViewModel")
struct QrScanViewModelTests {

    private func makeVM(handoff: PairingHandoff? = nil) -> (QrScanViewModel, NavigationRecorder) {
        let vm = QrScanViewModel(pairingHandoff: handoff ?? PairingHandoff())
        let recorder = NavigationRecorder()
        vm.onNavigate = { recorder.urls.append($0) }
        return (vm, recorder)
    }

    @Test func pairingLinkStashesTokenAndNavigatesWithURLOnly() throws {
        let handoff = PairingHandoff()
        let (vm, recorder) = makeVM(handoff: handoff)

        vm.onCodeScanned(PairingLinkFixture.link(json: #"{"v":1,"url":"http://192.168.1.20:18789","token":"tok_123"}"#))

        let url = try #require(recorder.urls.first)
        #expect(url.absoluteString == "http://192.168.1.20:18789")
        #expect(!url.absoluteString.contains("tok_123"))
        #expect(vm.lastDecoded == url)
        #expect(vm.error == nil)
        #expect(handoff.takePairing(for: url)?.token == "tok_123")
    }

    @Test func pairingLinkWithoutTokenStashesNothing() throws {
        let handoff = PairingHandoff()
        let (vm, recorder) = makeVM(handoff: handoff)

        vm.onCodeScanned(PairingLinkFixture.link(json: #"{"v":1,"url":"http://192.168.1.20:18789"}"#))

        let url = try #require(recorder.urls.first)
        #expect(handoff.takePairing(for: url) == nil)
    }

    @Test func rawGatewayURLNavigates() {
        let (vm, recorder) = makeVM()

        vm.onCodeScanned("  https://gw.example.com:18789  ")

        #expect(recorder.urls.map(\.absoluteString) == ["https://gw.example.com:18789"])
    }

    @Test func rawTunnelURLWithoutPortGets443() {
        let (vm, recorder) = makeVM()

        vm.onCodeScanned("https://abc-xyz.trycloudflare.com")

        #expect(recorder.urls.map(\.absoluteString) == ["https://abc-xyz.trycloudflare.com:443"])
    }

    @Test func rawURLWithUserinfoIsRejected() {
        let (vm, recorder) = makeVM()

        vm.onCodeScanned("http://admin:hunter2@192.168.1.20:18789")

        #expect(recorder.urls.isEmpty)
        #expect(vm.error == "QR code doesn't contain a valid gateway URL")
    }

    @Test func malformedPairingLinkSurfacesErrorAndDoesNotNavigate() {
        let (vm, recorder) = makeVM()

        vm.onCodeScanned("ghostcrab://pair?p=!!!!")

        #expect(recorder.urls.isEmpty)
        #expect(vm.error == "Pairing QR code is malformed: base64 decode failed")
    }

    @Test func unsupportedVersionIsReported() {
        let (vm, recorder) = makeVM()

        vm.onCodeScanned(PairingLinkFixture.link(json: #"{"v":2,"url":"http://192.168.1.20:18789"}"#))

        #expect(recorder.urls.isEmpty)
        #expect(vm.error == "Pairing QR code is malformed: unsupported pairing data version 2")
    }

    @Test func rejectedPairingLinkStashesNothingAndNeverEchoesTheToken() throws {
        let handoff = PairingHandoff()
        let (vm, recorder) = makeVM(handoff: handoff)

        // Valid token, but the gateway URL carries userinfo.
        vm.onCodeScanned(PairingLinkFixture.link(json: #"{"v":1,"url":"http://u:p@192.168.1.20:18789","token":"tok_123"}"#))

        #expect(recorder.urls.isEmpty)
        let message = try #require(vm.error)
        #expect(!message.contains("tok_123"))
        #expect(handoff.takePairing(for: try #require(URL(string: "http://192.168.1.20:18789"))) == nil)
    }

    @Test func oversizedPayloadIsRejectedBeforeDecoding() {
        let (vm, recorder) = makeVM()
        let name = String(repeating: "x", count: PairingLink.maxLength)

        vm.onCodeScanned(PairingLinkFixture.link(json: #"{"v":1,"url":"http://192.168.1.20:18789","name":"\#(name)"}"#))

        #expect(recorder.urls.isEmpty)
        #expect(vm.error == "QR code is too large (over 4096 characters)")
    }

    @Test func arbitraryTextIsRejected() {
        let (vm, recorder) = makeVM()
        vm.onCodeScanned("WIFI:S:home;T:WPA;P:hunter2;;")
        #expect(recorder.urls.isEmpty)
        #expect(vm.error == "QR code doesn't contain a valid gateway URL")
    }

    @Test func scanAgainClearsTheErrorAndStartsANewGeneration() {
        let (vm, recorder) = makeVM()
        vm.onCodeScanned("not a url")
        #expect(vm.error != nil)
        let generation = vm.scanGeneration

        vm.scanAgain()

        #expect(vm.error == nil)
        // A new generation is what makes the scanner bridge restart capture.
        #expect(vm.scanGeneration == generation + 1)
        vm.onCodeScanned("http://192.168.1.20:18789")
        #expect(recorder.urls.map(\.absoluteString) == ["http://192.168.1.20:18789"])
    }

    @Test func scannerThatCannotStartShowsWhyAndScanAgainRetries() {
        let (vm, _) = makeVM()

        vm.scannerDidFail(.cameraAccessDenied)

        #expect(vm.error == "Camera access is off for GhostCrab. Allow it in Settings > Privacy & Security > Camera, or use Manual Entry.")
        let generation = vm.scanGeneration
        vm.scanAgain()
        #expect(vm.error == nil)
        #expect(vm.scanGeneration == generation + 1)
    }

    @Test func unsupportedScannerPointsToManualEntry() {
        let (vm, _) = makeVM()
        vm.scannerDidFail(.unsupported)
        #expect(vm.error == "Live QR scanning is unavailable on this device. Use Manual Entry instead.")
    }

    @Test func restrictedCameraDoesNotSendTheUserToSettings() {
        let (vm, _) = makeVM()

        vm.scannerDidFail(.cameraRestricted)

        #expect(vm.error == "The camera is restricted on this device (Screen Time or device management), so GhostCrab can't turn it on. Use Manual Entry instead.")
        #expect(vm.error?.contains("Settings >") == false)
    }

    #if os(iOS) && !targetEnvironment(macCatalyst)
    @Test func cameraStatusSeparatesARefusalFromADevicePolicy() {
        #expect(QrScannerFailure.cameraBlocked(status: .denied) == .cameraAccessDenied)
        #expect(QrScannerFailure.cameraBlocked(status: .restricted) == .cameraRestricted)
        // Access granted but VisionKit still reports the camera restricted: a policy.
        #expect(QrScannerFailure.cameraBlocked(status: .authorized) == .cameraRestricted)
    }
    #endif

    @Test func unexpectedScannerErrorNamesItsType() {
        let (vm, _) = makeVM()
        vm.scannerDidFail(.startFailed(errorType: "ScanningUnavailable"))
        #expect(vm.error == "The camera couldn't start (ScanningUnavailable). Tap Scan again, or use Manual Entry.")
    }

    @Test func reappearingStartsANewGeneration() {
        let (vm, _) = makeVM()
        vm.onCodeScanned("http://192.168.1.20:18789")
        let generation = vm.scanGeneration

        vm.screenDidAppear()

        #expect(vm.scanGeneration == generation + 1)
    }
}
