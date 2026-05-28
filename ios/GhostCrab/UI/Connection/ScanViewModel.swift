import Foundation
import Observation

/// ViewModel for the LAN/Bonjour discovery screen.
///
/// Mirrors `ScanViewModel.kt` but simplified to the surface the iOS port needs:
/// the screen subscribes on appear, unsubscribes on disappear, and the VM
/// accumulates `DiscoveredGateway` results into an observable list.
///
/// The Android side also auto-connects on tap when a saved profile matches —
/// we surface the discovered gateway list only and let the screen route to
/// `.manualEntry(prefillURL:)` for confirmation, matching the spec's
/// "tap → navigate to manualEntry" behaviour.
@MainActor
@Observable
public final class ScanViewModel {

    // MARK: - Dependencies

    private let discoveryService: any DiscoveryService

    // MARK: - State

    /// Accumulated discovered gateways, deduplicated by `instanceName`.
    public private(set) var gateways: [DiscoveredGateway] = []

    /// `true` while a scan is in progress.
    public private(set) var isScanning: Bool = false

    /// Last error surfaced from discovery.
    public var lastError: String?

    /// `true` after the empty-result hint window (10s) has elapsed with no results.
    public private(set) var showEmptyHint: Bool = false

    private var scanTask: Task<Void, Never>?
    private var hintTask: Task<Void, Never>?

    // MARK: - Init

    public init(discoveryService: any DiscoveryService) {
        self.discoveryService = discoveryService
    }

    // MARK: - Lifecycle

    /// Called from `.task` on the view; starts the discovery subscription.
    public func start() {
        guard scanTask == nil else { return }
        self.gateways = []
        self.lastError = nil
        self.showEmptyHint = false
        self.isScanning = true

        let stream = discoveryService.startDiscovery()
        scanTask = Task { [weak self] in
            for await gateway in stream {
                guard let self else { return }
                await MainActor.run {
                    if !self.gateways.contains(where: { $0.instanceName == gateway.instanceName }) {
                        self.gateways.append(gateway)
                    }
                }
            }
            await MainActor.run { self?.isScanning = false }
        }

        hintTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 10_000_000_000)
            guard let self else { return }
            await MainActor.run {
                if self.gateways.isEmpty { self.showEmptyHint = true }
            }
        }
    }

    /// Called from `.onDisappear`; tears down the discovery subscription.
    public func stop() {
        scanTask?.cancel()
        scanTask = nil
        hintTask?.cancel()
        hintTask = nil
        self.isScanning = false
        Task { await self.discoveryService.stopDiscovery() }
    }
}
