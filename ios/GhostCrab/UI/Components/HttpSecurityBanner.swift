import SwiftUI

/// Amber-tinted callout shown above any form/view that operates over plain HTTP.
///
/// Mirrors the Android `HttpSecurityBanner` composable. The amber background +
/// amber outline + warning glyph are deliberate — silent security drift is the
/// project's #1 anti-goal, so HTTP-mode connections are *visually loud*.
///
/// Variants:
/// - Default: amber background, amber outline. Used when the connection is HTTP but auth is required.
/// - `httpWithoutAuth: true`: amber background, **crimson** outline. The worst case — no
///   encryption AND no auth — gets a crimson outline as a second warning channel.
public struct HttpSecurityBanner: View {

    private let httpWithoutAuth: Bool

    public init(httpWithoutAuth: Bool = false) {
        self.httpWithoutAuth = httpWithoutAuth
    }

    public var body: some View {
        HStack(alignment: .top, spacing: DesignTokens.Spacing.sm) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(DesignTokens.Color.amberWarn)
                .font(.system(size: 16))
                .padding(.top, 1)

            VStack(alignment: .leading, spacing: DesignTokens.Spacing.xs) {
                Text("Connection is unencrypted (HTTP). Anyone on this network can read configuration data.")
                    .font(AppFont.body(13))
                    .foregroundStyle(DesignTokens.Color.textPrimary)
                if httpWithoutAuth {
                    Text("No bearer token configured — anyone on this network can also reconfigure the gateway.")
                        .font(AppFont.body(13))
                        .foregroundStyle(DesignTokens.Color.crimsonError)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, DesignTokens.Spacing.md)
        .padding(.vertical, DesignTokens.Spacing.sm)
        .background(
            DesignTokens.Shape.small
                .fill(DesignTokens.Color.amberWarn.opacity(0.15))
        )
        .overlay(
            DesignTokens.Shape.small
                .strokeBorder(
                    httpWithoutAuth ? DesignTokens.Color.crimsonError : DesignTokens.Color.amberWarn,
                    lineWidth: 1
                )
        )
    }
}

#Preview("HTTP only") {
    ZStack {
        DesignTokens.Color.abyss.ignoresSafeArea()
        HttpSecurityBanner().padding()
    }
}

#Preview("HTTP + no auth") {
    ZStack {
        DesignTokens.Color.abyss.ignoresSafeArea()
        HttpSecurityBanner(httpWithoutAuth: true).padding()
    }
}
