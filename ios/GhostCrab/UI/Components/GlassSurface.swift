import SwiftUI

/// 6% white-alpha overlay over `DesignTokens.Color.abyss`, with a subtle outline.
///
/// The canonical surface wrapper for cards, panels, and inputs across the app.
/// Mirrors Android's `GlassSurface` composable — no solid gray cards anywhere
/// else in the UI tree.
///
/// **Usage:**
/// ```swift
/// GlassSurface {
///     VStack(alignment: .leading) {
///         Text("Connected")
///         Text(gateway.url).font(AppFont.mono(13))
///     }
///     .padding(DesignTokens.Spacing.md)
/// }
/// ```
public struct GlassSurface<Content: View>: View {

    private let cornerRadius: CGFloat
    private let content: Content

    /// - Parameters:
    ///   - cornerRadius: Defaults to `DesignTokens.Shape.mediumRadius` (12 pt — card scale).
    ///   - content: Surface content. Caller is responsible for inner padding.
    public init(
        cornerRadius: CGFloat = DesignTokens.Shape.mediumRadius,
        @ViewBuilder content: () -> Content
    ) {
        self.cornerRadius = cornerRadius
        self.content = content()
    }

    public var body: some View {
        content
            .background(
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .fill(DesignTokens.Color.glass)
            )
            .overlay(
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .strokeBorder(DesignTokens.Color.outline, lineWidth: 1)
            )
    }
}

#Preview {
    ZStack {
        DesignTokens.Color.abyss.ignoresSafeArea()
        GlassSurface {
            VStack(alignment: .leading, spacing: DesignTokens.Spacing.sm) {
                Text("OpenClaw Gateway")
                    .foregroundStyle(DesignTokens.Color.textPrimary)
                Text("192.168.0.239:3000")
                    .font(AppFont.mono(13))
                    .foregroundStyle(DesignTokens.Color.textSecondary)
            }
            .padding(DesignTokens.Spacing.md)
        }
        .padding()
    }
}
