import SwiftUI

/// LAN/Bonjour discovery screen — mirrors `ScanScreen.kt`.
///
/// Subscribes to `DiscoveryService.startDiscovery()` on appear, tears it down on
/// disappear. Each discovered gateway is rendered as a row showing the mDNS
/// instance name plus `host:port` in mono. Tapping a row pushes
/// `.manualEntry(prefillURL: http://host:port)` so the user can confirm the URL
/// and optionally provide a bearer token. A pulsing cyan dot indicates the
/// scan is active.
public struct ScanScreen: View {

    @Environment(\.navigate) private var navigate
    @Environment(\.dismiss) private var dismiss

    @State public var vm: ScanViewModel

    public init(vm: ScanViewModel) {
        self._vm = State(initialValue: vm)
    }

    public var body: some View {
        ZStack {
            DesignTokens.Color.abyss.ignoresSafeArea()

            VStack(spacing: 0) {
                scanIndicatorBar
                content
            }
        }
        .navigationTitle("Scan LAN")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(DesignTokens.Color.abyssRaised, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
        #endif
        .task {
            vm.start()
        }
        .onDisappear {
            vm.stop()
        }
    }

    // MARK: - Scan indicator

    @ViewBuilder
    private var scanIndicatorBar: some View {
        HStack(spacing: DesignTokens.Spacing.sm) {
            Image(systemName: "dot.radiowaves.left.and.right")
                .foregroundStyle(DesignTokens.Color.cyanPulse)
                .font(.system(size: 14))
                .symbolEffect(.pulse, options: .repeating, isActive: vm.isScanning)
            Text(vm.isScanning ? "Scanning…" : "Scan stopped")
                .font(AppFont.body(13))
                .foregroundStyle(DesignTokens.Color.textSecondary)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, DesignTokens.Spacing.md)
        .padding(.vertical, DesignTokens.Spacing.sm)
        .background(DesignTokens.Color.abyssRaised)
        .overlay(alignment: .bottom) {
            Rectangle()
                .fill(DesignTokens.Color.outline)
                .frame(height: 0.5)
        }
    }

    // MARK: - Body content

    @ViewBuilder
    private var content: some View {
        if vm.gateways.isEmpty {
            ZStack {
                if vm.showEmptyHint {
                    EmptyState(
                        icon: "wifi.exclamationmark",
                        title: "No gateways found",
                        message: "Make sure the gateway is on this network and advertising `_openclaw-gw._tcp`. mDNS may be blocked by Wi-Fi isolation.",
                        action: ("Enter URL manually", { navigate(.manualEntry(prefillURL: nil)) })
                    )
                } else {
                    EmptyState(
                        icon: "wifi",
                        title: "Looking for gateways…",
                        message: "Scanning your local network for OpenClaw gateways."
                    )
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            List {
                ForEach(vm.gateways, id: \.instanceName) { gateway in
                    Button {
                        if let url = URL(string: gateway.url) {
                            navigate(.manualEntry(prefillURL: url))
                        }
                    } label: {
                        GlassSurface {
                            VStack(alignment: .leading, spacing: DesignTokens.Spacing.xxs) {
                                Text(gateway.instanceName)
                                    .font(AppFont.bodyBold(15))
                                    .foregroundStyle(DesignTokens.Color.textPrimary)
                                Text("\(gateway.hostAddress):\(gateway.port)")
                                    .font(AppFont.mono(12))
                                    .foregroundStyle(DesignTokens.Color.textSecondary)
                                if let version = gateway.version {
                                    Text("v\(version)")
                                        .font(AppFont.monoMedium(11))
                                        .foregroundStyle(DesignTokens.Color.cyanPulse)
                                }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(DesignTokens.Spacing.md)
                        }
                    }
                    .buttonStyle(.plain)
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
                    .padding(.vertical, DesignTokens.Spacing.xs)
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .background(DesignTokens.Color.abyss)
        }
    }
}
