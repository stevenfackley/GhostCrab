import SwiftUI

/// Centered icon + title + body for "nothing to show" states.
///
/// Used by `ConfigEditorScreen` (`NoConfigApi`), the model picker when
/// the gateway has zero models configured, and any other view that needs
/// to communicate intentional emptiness rather than failure.
public struct EmptyState: View {

    private let icon: String
    private let title: String
    private let message: String
    private let action: (label: String, run: () -> Void)?

    public init(
        icon: String,
        title: String,
        message: String,
        action: (label: String, run: () -> Void)? = nil
    ) {
        self.icon = icon
        self.title = title
        self.message = message
        self.action = action
    }

    public var body: some View {
        VStack(spacing: DesignTokens.Spacing.md) {
            Image(systemName: icon)
                .font(.system(size: 40, weight: .light))
                .foregroundStyle(DesignTokens.Color.textSecondary)
            VStack(spacing: DesignTokens.Spacing.xs) {
                Text(title)
                    .font(AppFont.bodyBold(17))
                    .foregroundStyle(DesignTokens.Color.textPrimary)
                    .multilineTextAlignment(.center)
                Text(message)
                    .font(AppFont.body(14))
                    .foregroundStyle(DesignTokens.Color.textSecondary)
                    .multilineTextAlignment(.center)
            }
            if let action {
                Button(action.label, action: action.run)
                    .font(AppFont.bodyBold(14))
                    .foregroundStyle(DesignTokens.Color.cyanPrimary)
                    .padding(.top, DesignTokens.Spacing.sm)
            }
        }
        .padding(DesignTokens.Spacing.lg)
        .frame(maxWidth: 480)
    }
}

#Preview {
    ZStack {
        DesignTokens.Color.abyss.ignoresSafeArea()
        EmptyState(
            icon: "doc.text.magnifyingglass",
            title: "No editable configuration",
            message: "This gateway version doesn't expose a JSON config API at /config. Use the gateway's own web UI to edit settings."
        )
    }
}
