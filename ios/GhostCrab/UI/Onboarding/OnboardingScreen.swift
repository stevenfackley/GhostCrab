import SwiftUI

/// Multi-step onboarding walkthrough — direct port of `OnboardingScreen.kt` +
/// `steps/`.
///
/// SwiftUI does not give us the Android `OnboardingScaffold` for free; instead
/// we lean on `TabView(.page)` for the swipeable pager and overlay our own
/// top-of-screen controls (back / skip / dot indicator). The six visible steps
/// map 1:1 to the Kotlin sealed-enum cases; `.completed` is never rendered —
/// it triggers a route push to `.connectionPicker` via `OnboardingViewModel.isFinished`.
public struct OnboardingScreen: View {

    @Environment(\.navigate) private var navigate

    @State public var vm: OnboardingViewModel

    public init(vm: OnboardingViewModel) {
        self._vm = State(initialValue: vm)
    }

    private static let visibleSteps: [OnboardingStep] = [
        .welcome, .whatIsOpenClaw, .installGateway,
        .startGateway, .verifyRunning, .findOnNetwork,
    ]

    public var body: some View {
        ZStack {
            DesignTokens.Color.abyss.ignoresSafeArea()

            VStack(spacing: 0) {
                topBar
                    .padding(.horizontal, DesignTokens.Spacing.md)
                    .padding(.top, DesignTokens.Spacing.sm)

                TabView(selection: pagerBinding) {
                    ForEach(Self.visibleSteps, id: \.self) { stepCase in
                        stepView(for: stepCase)
                            .padding(.horizontal, DesignTokens.Spacing.md)
                            .padding(.vertical, DesignTokens.Spacing.lg)
                            .tag(stepCase)
                    }
                }
                #if os(iOS)
                .tabViewStyle(.page(indexDisplayMode: .never))
                #endif

                dotIndicator
                    .padding(.vertical, DesignTokens.Spacing.md)
            }
        }
        .navigationTitle(title(for: vm.step))
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(DesignTokens.Color.abyssRaised, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
        #endif
        .onChange(of: vm.isFinished) { _, finished in
            if finished { navigate(.connectionPicker) }
        }
    }

    // MARK: - Top bar (back + skip)

    @ViewBuilder
    private var topBar: some View {
        HStack {
            if vm.step != .welcome {
                Button {
                    vm.back()
                } label: {
                    HStack(spacing: DesignTokens.Spacing.xs) {
                        Image(systemName: "chevron.left")
                        Text("Back")
                    }
                    .font(AppFont.body(15))
                    .foregroundStyle(DesignTokens.Color.textSecondary)
                }
            }
            Spacer()
            Button("Skip") { vm.skip() }
                .font(AppFont.body(15))
                .foregroundStyle(DesignTokens.Color.textSecondary)
        }
        .frame(height: 28)
    }

    // MARK: - Dot indicator

    @ViewBuilder
    private var dotIndicator: some View {
        HStack(spacing: DesignTokens.Spacing.sm) {
            ForEach(Self.visibleSteps, id: \.self) { stepCase in
                Circle()
                    .fill(stepCase == vm.step
                          ? DesignTokens.Color.cyanPrimary
                          : DesignTokens.Color.outline)
                    .frame(width: 8, height: 8)
            }
        }
    }

    // MARK: - Pager binding

    /// Two-way binding between `vm.step` and the `TabView` selection.
    /// Forward swipes call `vm.next()`; backward swipes call `vm.back()`.
    /// Out-of-band cases (selection equals current or jumps to `.completed`) are ignored.
    private var pagerBinding: Binding<OnboardingStep> {
        Binding(
            get: { vm.step == .completed ? .findOnNetwork : vm.step },
            set: { newValue in
                guard newValue != vm.step, newValue != .completed else { return }
                if newValue.index > vm.step.index {
                    vm.next()
                } else {
                    vm.back()
                }
            }
        )
    }

    // MARK: - Step content

    @ViewBuilder
    private func stepView(for step: OnboardingStep) -> some View {
        ScrollView {
            switch step {
            case .welcome:        WelcomeStepView(onNext: vm.next, onSkip: vm.skip)
            case .whatIsOpenClaw: WhatIsOpenClawStepView(onNext: vm.next)
            case .installGateway: InstallGatewayStepView(onNext: vm.next)
            case .startGateway:   StartGatewayStepView(onNext: vm.next)
            case .verifyRunning:  VerifyRunningStepView(onNext: vm.next)
            case .findOnNetwork:  FindOnNetworkStepView(
                onScan: { navigate(.scan) },
                onManualEntry: { navigate(.manualEntry(prefillURL: nil)) }
            )
            case .completed:      EmptyView()
            }
        }
    }

    private func title(for step: OnboardingStep) -> String {
        switch step {
        case .welcome:        return "Welcome"
        case .whatIsOpenClaw: return "What is OpenClaw?"
        case .installGateway: return "Install the Gateway"
        case .startGateway:   return "Start the Gateway"
        case .verifyRunning:  return "Verify It's Running"
        case .findOnNetwork:  return "Find It on the Network"
        case .completed:      return ""
        }
    }
}

// MARK: - Step subviews

private struct WelcomeStepView: View {
    let onNext: () -> Void
    let onSkip: () -> Void

    var body: some View {
        VStack(spacing: DesignTokens.Spacing.lg) {
            Text("GhostCrab")
                .font(AppFont.bodyBlack(40))
                .foregroundStyle(DesignTokens.Color.cyanPrimary)

            Text("The native Android client for OpenClaw Gateway. Securely manage your gateway from your phone.")
                .font(AppFont.body(15))
                .foregroundStyle(DesignTokens.Color.textSecondary)
                .multilineTextAlignment(.center)

            Spacer(minLength: DesignTokens.Spacing.xl)

            Button(action: onNext) {
                Text("Get Started")
                    .font(AppFont.bodyBold(15))
                    .foregroundStyle(DesignTokens.Color.abyss)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, DesignTokens.Spacing.md)
                    .background(DesignTokens.Shape.small.fill(DesignTokens.Color.cyanPrimary))
            }

            Button(action: onSkip) {
                Text("Skip — I already have a Gateway")
                    .font(AppFont.body(14))
                    .foregroundStyle(DesignTokens.Color.textSecondary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, DesignTokens.Spacing.md)
                    .overlay(DesignTokens.Shape.small.strokeBorder(DesignTokens.Color.outline, lineWidth: 1))
            }
        }
    }
}

private struct WhatIsOpenClawStepView: View {
    let onNext: () -> Void

    private let bullets: [String] = [
        "Self-hosted gateway you run on your own machine.",
        "Runs the AI models you choose — local or cloud.",
        "Optional bearer-token auth for remote access.",
        "Discoverable on your LAN via mDNS.",
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: DesignTokens.Spacing.md) {
            ForEach(bullets, id: \.self) { bullet in
                HStack(alignment: .top, spacing: DesignTokens.Spacing.sm) {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundStyle(DesignTokens.Color.cyanPrimary)
                        .font(.system(size: 18))
                    Text(bullet)
                        .font(AppFont.body(14))
                        .foregroundStyle(DesignTokens.Color.textSecondary)
                }
            }

            Spacer(minLength: DesignTokens.Spacing.md)

            Button(action: onNext) {
                Text("Next")
                    .font(AppFont.bodyBold(15))
                    .foregroundStyle(DesignTokens.Color.abyss)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, DesignTokens.Spacing.md)
                    .background(DesignTokens.Shape.small.fill(DesignTokens.Color.cyanPrimary))
            }
        }
    }
}

private struct InstallGatewayStepView: View {
    let onNext: () -> Void

    private let tabs = ["Linux/macOS", "Windows", "Raspberry Pi", "Docker"]
    private let commands = [
        "curl -fsSL https://openclaw.ai/install.sh | sh",
        "irm https://openclaw.ai/install.ps1 | iex",
        "curl -fsSL https://openclaw.ai/install-rpi.sh | sh",
        "docker run -p 18789:18789 openclaw/gateway:latest",
    ]

    @State private var selectedTab: Int = 0

    var body: some View {
        VStack(alignment: .leading, spacing: DesignTokens.Spacing.md) {
            Picker("Platform", selection: $selectedTab) {
                ForEach(0..<tabs.count, id: \.self) { idx in
                    Text(tabs[idx]).tag(idx)
                }
            }
            #if os(iOS)
            .pickerStyle(.segmented)
            #endif

            shellBlock(commands[selectedTab])

            Link(destination: URL(string: "https://openclaw.ai/docs/install")!) {
                Text("Open install docs")
                    .font(AppFont.body(13))
                    .foregroundStyle(DesignTokens.Color.cyanPulse)
            }

            Spacer(minLength: DesignTokens.Spacing.sm)

            Button(action: onNext) {
                Text("Next")
                    .font(AppFont.bodyBold(15))
                    .foregroundStyle(DesignTokens.Color.abyss)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, DesignTokens.Spacing.md)
                    .background(DesignTokens.Shape.small.fill(DesignTokens.Color.cyanPrimary))
            }
        }
    }
}

private struct StartGatewayStepView: View {
    let onNext: () -> Void

    private let firewallTabs = ["Windows", "macOS", "Linux (UFW)", "Linux (firewalld)"]
    private let firewallCommands = [
        #"New-NetFirewallRule -DisplayName "OpenClaw Gateway" -Direction Inbound -Protocol TCP -LocalPort 18789 -Action Allow -Profile Private"#,
        "sudo /usr/libexec/ApplicationFirewall/socketfilterfw --add $(which openclaw)",
        "sudo ufw allow 18789/tcp",
        "sudo firewall-cmd --permanent --add-port=18789/tcp && sudo firewall-cmd --reload",
    ]

    @State private var firewallTab: Int = 0
    @State private var token: String = StartGatewayStepView.generateToken()

    var body: some View {
        VStack(alignment: .leading, spacing: DesignTokens.Spacing.md) {
            Text("Start the gateway by running this command on the host machine:")
                .font(AppFont.body(14))
                .foregroundStyle(DesignTokens.Color.textSecondary)

            shellBlock("openclaw gateway start --port 18789")

            Divider().background(DesignTokens.Color.outline)

            Text("Open the firewall on the host")
                .font(AppFont.bodyBold(14))
                .foregroundStyle(DesignTokens.Color.textPrimary)

            Text("Pick the OS and run the command below to allow port 18789 inbound.")
                .font(AppFont.body(13))
                .foregroundStyle(DesignTokens.Color.textSecondary)

            Picker("Firewall", selection: $firewallTab) {
                ForEach(0..<firewallTabs.count, id: \.self) { idx in
                    Text(firewallTabs[idx]).tag(idx)
                }
            }
            #if os(iOS)
            .pickerStyle(.segmented)
            #endif

            shellBlock(firewallCommands[firewallTab])

            Divider().background(DesignTokens.Color.outline)

            Text("Optional: configure a bearer token for auth")
                .font(AppFont.body(14))
                .foregroundStyle(DesignTokens.Color.textSecondary)

            HStack(spacing: DesignTokens.Spacing.sm) {
                shellBlock(token)
                Button {
                    token = StartGatewayStepView.generateToken()
                } label: {
                    Image(systemName: "arrow.clockwise")
                        .foregroundStyle(DesignTokens.Color.textSecondary)
                }
            }

            shellBlock("{\"auth\": {\"type\": \"token\", \"token\": \"\(token)\"}}")

            Text("Keep this token secret — anyone with it can reconfigure the gateway.")
                .font(AppFont.body(12))
                .foregroundStyle(DesignTokens.Color.amberWarn)

            Spacer(minLength: DesignTokens.Spacing.lg)

            Button(action: onNext) {
                Text("Next")
                    .font(AppFont.bodyBold(15))
                    .foregroundStyle(DesignTokens.Color.abyss)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, DesignTokens.Spacing.md)
                    .background(DesignTokens.Shape.small.fill(DesignTokens.Color.cyanPrimary))
            }
        }
    }

    /// Hex-encoded 32-byte token. Mirrors `domain/util/generateToken.kt`.
    static func generateToken() -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return bytes.map { String(format: "%02x", $0) }.joined()
    }
}

private struct VerifyRunningStepView: View {
    let onNext: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: DesignTokens.Spacing.md) {
            Text("On the host machine, open this URL in any browser:")
                .font(AppFont.body(14))
                .foregroundStyle(DesignTokens.Color.textSecondary)

            shellBlock("http://localhost:18789/health")

            Text("You should see this response:")
                .font(AppFont.body(14))
                .foregroundStyle(DesignTokens.Color.textSecondary)

            shellBlock(#"{"status":"ok"}"#)

            Divider().background(DesignTokens.Color.outline)

            Text("Test from your phone")
                .font(AppFont.bodyBold(14))
                .foregroundStyle(DesignTokens.Color.textPrimary)

            Text("On the same Wi-Fi, open this URL on your phone's browser, replacing the IP with the host's LAN address:")
                .font(AppFont.body(13))
                .foregroundStyle(DesignTokens.Color.textSecondary)

            shellBlock("http://[your-machine-ip]:18789/health")

            Text("Find the host IP via `ipconfig` (Windows) or `ifconfig`/`ip addr` (macOS/Linux).")
                .font(AppFont.body(12))
                .foregroundStyle(DesignTokens.Color.textSecondary)

            Spacer(minLength: DesignTokens.Spacing.lg)

            Button(action: onNext) {
                Text("It's running — next")
                    .font(AppFont.bodyBold(15))
                    .foregroundStyle(DesignTokens.Color.cyanPrimary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, DesignTokens.Spacing.md)
                    .overlay(DesignTokens.Shape.small.strokeBorder(DesignTokens.Color.cyanPrimary, lineWidth: 1))
            }
        }
    }
}

private struct FindOnNetworkStepView: View {
    let onScan: () -> Void
    let onManualEntry: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: DesignTokens.Spacing.md) {
            Text("How would you like to find your gateway?")
                .font(AppFont.body(14))
                .foregroundStyle(DesignTokens.Color.textSecondary)

            Button(action: onScan) {
                GlassSurface {
                    HStack(alignment: .center, spacing: DesignTokens.Spacing.md) {
                        Image(systemName: "magnifyingglass")
                            .foregroundStyle(DesignTokens.Color.cyanPrimary)
                            .font(.system(size: 22))
                        VStack(alignment: .leading, spacing: DesignTokens.Spacing.xxs) {
                            Text("Auto-discover")
                                .font(AppFont.bodyBold(15))
                                .foregroundStyle(DesignTokens.Color.cyanPrimary)
                            Text("Scan your local Wi-Fi for gateways")
                                .font(AppFont.body(12))
                                .foregroundStyle(DesignTokens.Color.textSecondary)
                        }
                        Spacer(minLength: 0)
                    }
                    .padding(DesignTokens.Spacing.md)
                }
            }
            .buttonStyle(.plain)

            Button(action: onManualEntry) {
                GlassSurface {
                    HStack(alignment: .center, spacing: DesignTokens.Spacing.md) {
                        Image(systemName: "square.and.pencil")
                            .foregroundStyle(DesignTokens.Color.textSecondary)
                            .font(.system(size: 22))
                        VStack(alignment: .leading, spacing: DesignTokens.Spacing.xxs) {
                            Text("Enter URL manually")
                                .font(AppFont.bodyBold(15))
                                .foregroundStyle(DesignTokens.Color.textSecondary)
                            Text("Type the gateway URL directly")
                                .font(AppFont.body(12))
                                .foregroundStyle(DesignTokens.Color.textSecondary)
                        }
                        Spacer(minLength: 0)
                    }
                    .padding(DesignTokens.Spacing.md)
                }
            }
            .buttonStyle(.plain)
        }
    }
}

// MARK: - Shared shell-command block

@ViewBuilder
private func shellBlock(_ code: String) -> some View {
    GlassSurface {
        HStack(alignment: .top, spacing: DesignTokens.Spacing.sm) {
            Text(code)
                .font(AppFont.mono(13))
                .foregroundStyle(DesignTokens.Color.textPrimary)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button {
                #if os(iOS)
                UIPasteboard.general.string = code
                #endif
            } label: {
                Image(systemName: "doc.on.doc")
                    .foregroundStyle(DesignTokens.Color.textSecondary)
                    .font(.system(size: 14))
            }
        }
        .padding(DesignTokens.Spacing.md)
    }
}
