import SwiftUI

/// Picker for saved gateway connection profiles plus entry points for scan / QR /
/// manual entry. Mirrors `ConnectionPickerScreen.kt`.
///
/// Behaviour:
/// - Lists saved profiles via `List` with `.swipeActions` for delete.
/// - Shows `EmptyState` when no profiles exist.
/// - Surfaces `ConnectionStatusBar` at the top when the gateway is currently
///   `.connecting` or `.error`.
/// - Tap a profile → auto-connect via VM; on success VM flips `navigateToDashboard`
///   and `navigate.toDashboard()` shows the dashboard directly on the picker.
/// - `PAIRING_REQUIRED` → ``PairingApprovalCard`` above the list (instead of the
///   status bar) while the VM retries; the QR / LAN / Add actions are hidden
///   meanwhile, and leaving the screen cancels the wait.
/// - While a connect or its approval wait runs, the other rows are dimmed and
///   take no tap or swipe (``ConnectionPickerViewModel/profilesSelectable``).
public struct ConnectionPickerScreen: View {

    @Environment(\.navigate) private var navigate

    @State public var vm: ConnectionPickerViewModel

    public init(vm: ConnectionPickerViewModel) {
        self._vm = State(initialValue: vm)
    }

    public var body: some View {
        ZStack {
            DesignTokens.Color.abyss.ignoresSafeArea()

            VStack(spacing: 0) {
                if let prompt = vm.pairingPrompt {
                    PairingApprovalCard(
                        prompt: prompt,
                        onCheckNow: { vm.checkPairingNow() },
                        onCancel: { vm.cancelPairing() }
                    )
                    .padding(.horizontal, DesignTokens.Spacing.md)
                    .padding(.top, DesignTokens.Spacing.sm)
                } else if shouldShowStatusBar {
                    ConnectionStatusBar(connection: vm.connectionState)
                }

                if vm.profiles.isEmpty {
                    emptyStateView
                } else {
                    profileList
                }
            }
        }
        .navigationTitle("Gateways")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(DesignTokens.Color.abyssRaised, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
        #endif
        .toolbar {
            // Hidden while the approval card shows: those screens would leave the wait running behind them.
            if vm.pairingPrompt == nil {
                ToolbarItemGroup(placement: .primaryAction) {
                    Button {
                        navigate(.qrScan)
                    } label: {
                        Image(systemName: "qrcode.viewfinder")
                            .foregroundStyle(DesignTokens.Color.cyanPrimary)
                    }
                    Button {
                        navigate(.scan)
                    } label: {
                        Image(systemName: "magnifyingglass")
                            .foregroundStyle(DesignTokens.Color.textSecondary)
                    }
                    Button {
                        navigate(.manualEntry(prefillURL: nil))
                    } label: {
                        Image(systemName: "plus")
                            .foregroundStyle(DesignTokens.Color.cyanPrimary)
                    }
                }
            }
        }
        .onDisappear {
            vm.cancelPairing()
        }
        .onChange(of: vm.navigateToDashboard) { _, ready in
            if ready {
                navigate.toDashboard()
                vm.consumeNavigation()
            }
        }
        .alert(
            "Connection failed",
            isPresented: Binding(
                get: { vm.lastError != nil },
                set: { if !$0 { vm.lastError = nil } }
            ),
            presenting: vm.lastError
        ) { _ in
            Button("OK") { vm.lastError = nil }
        } message: { msg in
            Text(msg).font(AppFont.mono(13))
        }
    }

    // MARK: - Helpers

    private var shouldShowStatusBar: Bool {
        switch vm.connectionState {
        case .connecting, .error: return true
        case .disconnected, .connected: return false
        }
    }

    @ViewBuilder
    private var emptyStateView: some View {
        VStack(spacing: DesignTokens.Spacing.lg) {
            Spacer()
            EmptyState(
                icon: "qrcode.viewfinder",
                title: "Scan QR to connect",
                message: "Open your gateway's connect page in a browser:\nhttp://<gateway-ip>:19999",
                action: ("Open Camera", { navigate(.qrScan) })
            )
            HStack(spacing: DesignTokens.Spacing.sm) {
                Button {
                    navigate(.scan)
                } label: {
                    HStack {
                        Image(systemName: "magnifyingglass")
                        Text("Scan LAN")
                    }
                    .font(AppFont.body(14))
                    .foregroundStyle(DesignTokens.Color.cyanPrimary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, DesignTokens.Spacing.md)
                    .overlay(DesignTokens.Shape.small.strokeBorder(DesignTokens.Color.outline, lineWidth: 1))
                }
                Button {
                    navigate(.manualEntry(prefillURL: nil))
                } label: {
                    HStack {
                        Image(systemName: "plus")
                        Text("Add manually")
                    }
                    .font(AppFont.body(14))
                    .foregroundStyle(DesignTokens.Color.cyanPrimary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, DesignTokens.Spacing.md)
                    .overlay(DesignTokens.Shape.small.strokeBorder(DesignTokens.Color.outline, lineWidth: 1))
                }
            }
            .padding(.horizontal, DesignTokens.Spacing.lg)
            Spacer()
        }
    }

    @ViewBuilder
    private var profileList: some View {
        List {
            ForEach(vm.profiles) { profile in
                profileRow(profile)
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
                    .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                        if vm.profilesSelectable {
                            Button(role: .destructive) {
                                vm.delete(profile.id)
                            } label: {
                                Label("Delete", systemImage: "trash")
                            }
                        }
                    }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(DesignTokens.Color.abyss)
    }

    @ViewBuilder
    private func profileRow(_ profile: ConnectionProfile) -> some View {
        Button {
            vm.connect(profile)
        } label: {
            GlassSurface {
                HStack(alignment: .center, spacing: DesignTokens.Spacing.md) {
                    VStack(alignment: .leading, spacing: DesignTokens.Spacing.xxs) {
                        Text(profile.displayName)
                            .font(AppFont.bodyBold(15))
                            .foregroundStyle(DesignTokens.Color.textPrimary)
                        Text(profile.url)
                            .font(AppFont.mono(12))
                            .foregroundStyle(DesignTokens.Color.textSecondary)
                        if profile.hasToken {
                            Text("TOKEN AUTH")
                                .font(AppFont.monoMedium(10))
                                .foregroundStyle(DesignTokens.Color.cyanPulse)
                        }
                    }
                    Spacer(minLength: 0)
                    if vm.connectingId == profile.id {
                        ProgressView()
                            .progressViewStyle(.circular)
                            .tint(DesignTokens.Color.cyanPrimary)
                    } else {
                        Image(systemName: "chevron.right")
                            .foregroundStyle(DesignTokens.Color.textSecondary)
                            .font(.system(size: 14))
                    }
                }
                .padding(DesignTokens.Spacing.md)
            }
        }
        .buttonStyle(.plain)
        .disabled(!vm.profilesSelectable)
        // The plain style does not dim a disabled row; the one being connected stays bright beside its spinner.
        .opacity(vm.profilesSelectable || vm.connectingId == profile.id ? 1.0 : 0.6)
        .padding(.vertical, DesignTokens.Spacing.xs)
    }
}
