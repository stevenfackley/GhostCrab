import SwiftUI

/// Root theme modifier. Wraps any view in GhostCrab's design system defaults.
///
/// Mirrors `GhostCrabTheme.kt`. v1.0 is dark-mode only:
/// - Forces a dark color scheme.
/// - Paints the full background with `DesignTokens.Color.abyss`.
/// - Sets the default foreground (text) color to `.white`.
/// - Sets the default app font to Inter Regular at 16 pt — individual views
///   override with `AppFont.mono*` for code/IPs/JSON.
struct GhostCrabThemeModifier: ViewModifier {

    func body(content: Content) -> some View {
        ZStack {
            DesignTokens.Color.abyss
                .ignoresSafeArea()

            content
                .foregroundStyle(.white)
                .font(AppFont.body(16))
                .tint(DesignTokens.Color.cyanPrimary)
        }
        .preferredColorScheme(.dark)
    }
}

extension View {

    /// Applies the GhostCrab dark-mode design system to this view tree.
    ///
    /// Call once at the root of the app (e.g. inside `RootView`). Equivalent of
    /// wrapping the Compose tree in `GhostCrabTheme { … }` on Android.
    func ghostCrabTheme() -> some View {
        modifier(GhostCrabThemeModifier())
    }
}
