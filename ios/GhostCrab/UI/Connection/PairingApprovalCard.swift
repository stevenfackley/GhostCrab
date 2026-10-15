import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

/// Shown while a connect waits for `openclaw devices approve` on the gateway host (ADR-0007 decision 7). Nothing is
/// approved from the phone: the card names the gateway, the request and the exact command (JetBrains Mono, on its own
/// line, selectable and copyable), explains the request with the manager's warnings (amber), and the connect retries
/// every 5 s. The spinner marks the active wait. Mirrors Android's `PairingApprovalCard`.
struct PairingApprovalCard: View {

    let prompt: PairingPrompt
    let onCheckNow: () -> Void
    let onCancel: () -> Void

    var body: some View {
        GlassSurface {
            VStack(alignment: .leading, spacing: DesignTokens.Spacing.sm) {
                header
                ForEach(Array(prompt.warnings.enumerated()), id: \.offset) { _, warning in
                    Text(verbatim: warning.userMessage)
                        .font(AppFont.body(12))
                        .foregroundStyle(DesignTokens.Color.amberWarn)
                        .fixedSize(horizontal: false, vertical: true)
                }
                instructions
                Text("GhostCrab checks again every 5 seconds for up to 5 minutes. You can also approve it in the Control UI under Devices.")
                    .font(AppFont.body(12))
                    .foregroundStyle(DesignTokens.Color.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                actions
            }
            .padding(DesignTokens.Spacing.md)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var header: some View {
        HStack(spacing: DesignTokens.Spacing.sm) {
            ProgressView()
                .progressViewStyle(.circular)
                .controlSize(.small)
                .tint(DesignTokens.Color.cyanPulse)
            Text("Waiting for approval on the gateway")
                .font(AppFont.bodyBold(15))
                .foregroundStyle(DesignTokens.Color.amberWarn)
        }
    }

    @ViewBuilder
    private var instructions: some View {
        Text(verbatim: "Gateway \(prompt.url)")
            .font(AppFont.mono(12))
            .foregroundStyle(DesignTokens.Color.textPrimary)
            .textSelection(.enabled)
        if let requestId = prompt.requestId {
            Text(verbatim: "Request \(requestId)")
                .font(AppFont.mono(12))
                .foregroundStyle(DesignTokens.Color.textPrimary)
                .textSelection(.enabled)
        }
        Text(verbatim: "This device is not approved yet (\(prompt.reason)). OpenClaw never auto-approves remote devices. On the gateway host run:")
            .font(AppFont.body(13))
            .foregroundStyle(DesignTokens.Color.textSecondary)
            .fixedSize(horizontal: false, vertical: true)
        Text(verbatim: prompt.approveCommand)
            .font(AppFont.mono(13))
            .foregroundStyle(DesignTokens.Color.textPrimary)
            .textSelection(.enabled)
            .padding(DesignTokens.Spacing.sm)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(DesignTokens.Shape.small.fill(DesignTokens.Color.abyss))
            .overlay(DesignTokens.Shape.small.strokeBorder(DesignTokens.Color.outline, lineWidth: 1))
        if !prompt.requestedScopes.isEmpty {
            Text(verbatim: "Requested scopes: \(prompt.requestedScopes.joined(separator: ", "))")
                .font(AppFont.mono(11))
                .foregroundStyle(DesignTokens.Color.textSecondary)
        }
    }

    private var actions: some View {
        HStack(spacing: DesignTokens.Spacing.md) {
            Button("Copy command") { copy(prompt.approveCommand) }
                .buttonStyle(.borderless)
            Button("Check now", action: onCheckNow)
                .buttonStyle(.borderless)
            Spacer(minLength: 0)
            Button("Cancel", role: .cancel, action: onCancel)
                .buttonStyle(.borderless)
        }
        .font(AppFont.bodyMedium(14))
        .tint(DesignTokens.Color.cyanPrimary)
    }

    private func copy(_ text: String) {
        #if canImport(UIKit)
        UIPasteboard.general.string = text
        #endif
    }
}

#Preview {
    ZStack {
        DesignTokens.Color.abyss.ignoresSafeArea()
        PairingApprovalCard(
            prompt: PairingPrompt(
                url: "ws://192.168.1.20:18789/ws",
                requestId: "af0b5d81-7bd2-4b86-80b0-4a489b541974",
                reason: "not-paired",
                requestedScopes: ["operator.read", "operator.write"],
                warnings: [.deviceIdentityRegenerated]
            ),
            onCheckNow: {},
            onCancel: {}
        )
        .padding(DesignTokens.Spacing.md)
    }
}
