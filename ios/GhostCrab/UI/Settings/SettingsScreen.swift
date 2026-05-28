import SwiftUI

/// Settings screen — security toggles, onboarding replay, app info, links.
///
/// Mirrors the Android `SettingsScreen.kt` per the iOS design spec
/// (Screen parity map → `SettingsScreen`). Uses SwiftUI `Form` as the canonical
/// iOS settings idiom rather than a hand-rolled card layout.
public struct SettingsScreen: View {

    @Environment(\.appContainer) private var container
    @Environment(\.navigate) private var navigate
    @Environment(\.openURL) private var openURL

    @State private var vm: SettingsViewModel?

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
        .navigationTitle("Settings")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(DesignTokens.Color.abyssRaised, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
        #endif
        .task {
            // Build the VM (cheap, idempotent) and start the cleartext-pref stream.
            // Doing this here (rather than in `onAppear`) guarantees the same task
            // both owns the VM lifetime and runs the observer.
            if vm == nil { vm = container.makeSettingsVM() }
            await vm?.observe()
        }
    }

    // MARK: - Form

    @ViewBuilder
    private func content(vm: SettingsViewModel) -> some View {
        Form {
            securitySection(vm: vm)
            onboardingSection(vm: vm)
            aboutSection
            supportSection
        }
        .scrollContentBackground(.hidden)
        .background(DesignTokens.Color.abyss)
        .tint(DesignTokens.Color.cyanPrimary)
        .alert(
            "Onboarding reset",
            isPresented: Binding(
                get: { vm.onboardingResetSuccess },
                set: { _ in vm.acknowledgeOnboardingReset() }
            )
        ) {
            Button("Show now") {
                vm.acknowledgeOnboardingReset()
                navigate(.onboarding)
            }
            Button("Later", role: .cancel) {
                vm.acknowledgeOnboardingReset()
            }
        } message: {
            Text("The walkthrough will appear again the next time you launch the app.")
        }
    }

    // MARK: - Security section

    @ViewBuilder
    private func securitySection(vm: SettingsViewModel) -> some View {
        Section {
            Toggle(
                isOn: Binding(
                    get: { vm.allowCleartextPublicIPs },
                    set: { vm.setAllowCleartextPublicIPs($0) }
                )
            ) {
                VStack(alignment: .leading, spacing: DesignTokens.Spacing.xs) {
                    Text("Allow HTTP to public IPs")
                        .font(AppFont.body(15))
                        .foregroundStyle(DesignTokens.Color.textPrimary)
                }
            }
            .toggleStyle(SwitchToggleStyle(tint: DesignTokens.Color.amberWarn))
            .listRowBackground(DesignTokens.Color.glass)
        } header: {
            Text("Security")
                .font(AppFont.bodyBold(12))
                .foregroundStyle(DesignTokens.Color.cyanPrimary)
        } footer: {
            Text("Disables the cleartext-IP guard for non-private addresses. Loopback and RFC-1918 ranges are always allowed. Leave this off unless you knowingly connect to a public-IP gateway over HTTP.")
                .font(AppFont.body(12))
                .foregroundStyle(DesignTokens.Color.textSecondary)
        }
    }

    // MARK: - Onboarding section

    @ViewBuilder
    private func onboardingSection(vm: SettingsViewModel) -> some View {
        Section {
            Button {
                vm.resetOnboarding()
            } label: {
                HStack {
                    Text("Replay onboarding")
                        .font(AppFont.body(15))
                        .foregroundStyle(DesignTokens.Color.cyanPrimary)
                    Spacer()
                    Image(systemName: "arrow.counterclockwise")
                        .foregroundStyle(DesignTokens.Color.cyanPrimary)
                }
            }
            .listRowBackground(DesignTokens.Color.glass)
        } header: {
            Text("Onboarding")
                .font(AppFont.bodyBold(12))
                .foregroundStyle(DesignTokens.Color.cyanPrimary)
        } footer: {
            Text("Clears the completion flag and re-runs the walkthrough on the next launch.")
                .font(AppFont.body(12))
                .foregroundStyle(DesignTokens.Color.textSecondary)
        }
    }

    // MARK: - About section

    @ViewBuilder
    private var aboutSection: some View {
        Section {
            aboutRow(label: "Version", value: BuildInfo.marketingVersion)
            aboutRow(label: "Build", value: BuildInfo.buildNumber, mono: true)
            aboutRow(label: "Git SHA", value: BuildInfo.gitSHA, mono: true)
            aboutRow(label: "Bundle ID", value: "com.qavren.ghostcrab", mono: true)
            aboutRow(label: "Apple Team ID", value: "QJW4S8BDFX", mono: true)
            aboutRow(label: "Copyright", value: "\u{00A9} 2026 Steven Fackley")
        } header: {
            Text("About")
                .font(AppFont.bodyBold(12))
                .foregroundStyle(DesignTokens.Color.cyanPrimary)
        }
    }

    // MARK: - Privacy & Support section

    @ViewBuilder
    private var supportSection: some View {
        Section {
            Button {
                if let url = URL(string: "https://getghostcrab.com/privacy") {
                    openURL(url)
                }
            } label: {
                linkRow(label: "Privacy Policy", systemImage: "lock.shield")
            }
            .listRowBackground(DesignTokens.Color.glass)

            Button {
                if let url = URL(string: "https://github.com/stevenfackley/GhostCrab/issues") {
                    openURL(url)
                }
            } label: {
                linkRow(label: "Report an issue", systemImage: "ladybug")
            }
            .listRowBackground(DesignTokens.Color.glass)
        } header: {
            Text("Privacy & Support")
                .font(AppFont.bodyBold(12))
                .foregroundStyle(DesignTokens.Color.cyanPrimary)
        }
    }

    // MARK: - Row helpers

    @ViewBuilder
    private func aboutRow(label: String, value: String, mono: Bool = false) -> some View {
        HStack {
            Text(label)
                .font(AppFont.body(14))
                .foregroundStyle(DesignTokens.Color.textSecondary)
            Spacer()
            Text(value)
                .font(mono ? AppFont.mono(13) : AppFont.body(14))
                .foregroundStyle(DesignTokens.Color.textPrimary)
                .textSelection(.enabled)
        }
        .listRowBackground(DesignTokens.Color.glass)
    }

    @ViewBuilder
    private func linkRow(label: String, systemImage: String) -> some View {
        HStack {
            Image(systemName: systemImage)
                .foregroundStyle(DesignTokens.Color.cyanPrimary)
            Text(label)
                .font(AppFont.body(15))
                .foregroundStyle(DesignTokens.Color.cyanPrimary)
            Spacer()
            Image(systemName: "arrow.up.right.square")
                .foregroundStyle(DesignTokens.Color.textSecondary)
                .font(.system(size: 14))
        }
    }

}
