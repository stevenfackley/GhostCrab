import Foundation
import Network

/// Production [DiscoveryService] backed by `Network.framework`'s `NWBrowser`.
///
/// Browses for `_openclaw-gw._tcp` Bonjour services on the local network and resolves
/// each result to a host + port via a short-lived `NWConnection`. Emits a
/// `DiscoveredGateway` for every newly-resolved instance.
///
/// ## Behaviour
///
/// - **Lifecycle:** A fresh `NWBrowser` is created per call to `startDiscovery()`. The
///   browser starts when the `AsyncStream` is first iterated and is cancelled when the
///   consuming task is cancelled, the stream is finished, or `stopDiscovery()` is called.
/// - **Deduplication:** Keyed by mDNS instance name (`NWEndpoint.Service.name`). Repeat
///   resolutions for an already-emitted instance are dropped, matching
///   `NsdDiscoveryServiceImpl` semantics on Android.
/// - **Best-effort:** Browser state failures (`.failed`, `.cancelled`) finish the stream
///   without surfacing an error. Individual endpoint resolution failures are silently
///   skipped — matches the Kotlin implementation's "skip on resolve failure" contract.
/// - **TXT records:** `displayName` and `version` are read from the TXT record when
///   present. `displayName` falls back to the instance name; `version` falls back to
///   `nil`.
///
/// ## Concurrency
///
/// `NWBrowser` callbacks land on the supplied dispatch queue (`com.qavren.ghostcrab.discovery`).
/// All shared state is gated through the queue, so the class is `Sendable` despite holding
/// mutable internal state.
///
/// > Important: Requires `NSBonjourServices` in `Info.plist` to contain
/// > `_openclaw-gw._tcp`, otherwise iOS 14+ silently blocks the browser.
public final class NWBrowserDiscoveryService: DiscoveryService, @unchecked Sendable {

    private static let serviceType = "_openclaw-gw._tcp"
    private static let resolveTimeout: TimeInterval = 5.0

    /// Serial queue serving NWBrowser/NWConnection callbacks and guarding mutable state.
    private let queue = DispatchQueue(label: "com.qavren.ghostcrab.discovery")

    /// The currently-active browser, if any. Accessed only on `queue`.
    private var activeBrowser: NWBrowser?

    /// Continuation for the currently-active stream, if any. Accessed only on `queue`.
    private var activeContinuation: AsyncStream<DiscoveredGateway>.Continuation?

    public init() {}

    // MARK: - DiscoveryService

    /// Starts a fresh mDNS browse and returns an `AsyncStream` of resolved gateways.
    ///
    /// The browser is started on the first iteration of the stream and torn down on
    /// termination (cancellation, finish, or explicit `stopDiscovery()`).
    public func startDiscovery() -> AsyncStream<DiscoveredGateway> {
        AsyncStream { continuation in
            queue.async { [weak self] in
                guard let self else {
                    continuation.finish()
                    return
                }
                self.beginBrowse(continuation: continuation)
            }

            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                self.queue.async {
                    self.teardown()
                }
            }
        }
    }

    /// Cancels any active browse, finishing the corresponding `AsyncStream`.
    public func stopDiscovery() async {
        await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
            queue.async {
                self.teardown()
                cont.resume()
            }
        }
    }

    // MARK: - Private

    /// Must be called on `queue`.
    private func beginBrowse(continuation: AsyncStream<DiscoveredGateway>.Continuation) {
        // Replace any prior session — only one active browse at a time.
        teardown()

        var seen = Set<String>()

        let descriptor = NWBrowser.Descriptor.bonjour(type: Self.serviceType, domain: nil)
        let params = NWParameters()
        params.includePeerToPeer = true
        let browser = NWBrowser(for: descriptor, using: params)

        browser.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .failed, .cancelled:
                // Best-effort discovery: finish without raising.
                self.activeContinuation?.finish()
                self.activeContinuation = nil
                self.activeBrowser = nil
            case .setup, .ready, .waiting:
                break
            @unknown default:
                break
            }
        }

        browser.browseResultsChangedHandler = { [weak self] results, _ in
            guard let self else { return }
            for result in results {
                guard case let .service(name, _, _, _) = result.endpoint else { continue }
                guard seen.insert(name).inserted else { continue }
                self.resolve(result: result, instanceName: name, continuation: continuation)
            }
        }

        activeBrowser = browser
        activeContinuation = continuation
        browser.start(queue: queue)
    }

    /// Resolves a single browse result to a host + port via a short-lived `NWConnection`
    /// and emits a `DiscoveredGateway` if successful. Silently skips on failure or timeout.
    private func resolve(
        result: NWBrowser.Result,
        instanceName: String,
        continuation: AsyncStream<DiscoveredGateway>.Continuation
    ) {
        let (displayName, version) = txtFields(from: result.metadata, fallbackName: instanceName)

        let connection = NWConnection(to: result.endpoint, using: .tcp)
        var didEmit = false

        let finish: () -> Void = { [weak connection] in
            connection?.stateUpdateHandler = nil
            connection?.cancel()
        }

        // Timeout: cancel the resolution attempt after `resolveTimeout` seconds.
        queue.asyncAfter(deadline: .now() + Self.resolveTimeout) {
            if !didEmit { finish() }
        }

        connection.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready, .preparing:
                // `currentPath` becomes meaningful as soon as the endpoint resolves; we
                // pull host/port from the connection's current endpoint.
                guard let (host, port) = self.hostPort(from: connection) else {
                    if state == .ready { finish() }
                    return
                }
                guard !didEmit else { return }
                didEmit = true
                continuation.yield(
                    DiscoveredGateway(
                        instanceName: instanceName,
                        hostAddress: host,
                        port: Int(port),
                        displayName: displayName,
                        version: version
                    )
                )
                finish()
            case .failed, .cancelled:
                finish()
            case .setup, .waiting:
                break
            @unknown default:
                break
            }
        }

        connection.start(queue: queue)
    }

    /// Extracts `(host, port)` from a connection's resolved endpoint.
    ///
    /// Prefers `currentPath.remoteEndpoint` (populated once the endpoint is resolved by
    /// `Network.framework`); falls back to the connection's own `endpoint`.
    private func hostPort(from connection: NWConnection) -> (String, UInt16)? {
        let endpoint = connection.currentPath?.remoteEndpoint ?? connection.endpoint
        guard case let .hostPort(host, port) = endpoint else { return nil }
        return (Self.hostString(host), port.rawValue)
    }

    /// Renders an `NWEndpoint.Host` to a plain numeric/string address.
    private static func hostString(_ host: NWEndpoint.Host) -> String {
        switch host {
        case .ipv4(let addr):
            return addr.debugDescription
        case .ipv6(let addr):
            // Strip the `%interface` scope-id suffix if present; not part of a URL host.
            let raw = addr.debugDescription
            if let percent = raw.firstIndex(of: "%") {
                return String(raw[..<percent])
            }
            return raw
        case .name(let name, _):
            return name
        @unknown default:
            return ""
        }
    }

    /// Reads `displayName` and `version` from a `bonjour` TXT-record metadata blob.
    private func txtFields(
        from metadata: NWBrowser.Result.Metadata,
        fallbackName: String
    ) -> (displayName: String, version: String?) {
        guard case let .bonjour(txt) = metadata else {
            return (fallbackName, nil)
        }
        let display = txt["displayName"].flatMap { $0.isEmpty ? nil : $0 } ?? fallbackName
        let version = txt["version"].flatMap { $0.isEmpty ? nil : $0 }
        return (display, version)
    }

    /// Cancels any active browser/continuation. Must be called on `queue`.
    private func teardown() {
        activeBrowser?.cancel()
        activeBrowser = nil
        activeContinuation?.finish()
        activeContinuation = nil
    }
}
