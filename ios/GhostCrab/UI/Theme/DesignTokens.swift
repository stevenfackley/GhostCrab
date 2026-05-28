import SwiftUI

/// Single source of truth for all GhostCrab brand colors, spacing, shape, and font
/// references on iOS.
///
/// Mirrors `app/.../ui/theme/BrandTokens.kt`, `Spacing.kt`, and `Shape.kt` verbatim.
/// All other UI code consumes these tokens — no hardcoded hex values, dp/CGFloat
/// magic numbers, or corner radii elsewhere in the iOS codebase.
enum DesignTokens {

    // MARK: - Color

    /// Brand palette — direct port of `BrandTokens.kt`.
    enum Color {

        // ── Backgrounds ─────────────────────────────────────────────────────

        /// Primary background — deep abyss dark.
        static let abyss = SwiftUI.Color(hex: 0x0F1115)

        /// Raised surface base — slightly lighter than `abyss`.
        static let abyssRaised = SwiftUI.Color(hex: 0x16191F)

        /// Glass surface overlay — 6% white-alpha over `abyss`.
        static let glass = SwiftUI.Color.white.opacity(Double(glassSurfaceAlpha))

        // ── Brand / Status ──────────────────────────────────────────────────

        /// Primary actions, connected state — luminous cyan.
        static let cyanPrimary = SwiftUI.Color(hex: 0x5BE9FF)

        /// Scanning / mDNS pulse — pale azure.
        static let cyanPulse = SwiftUI.Color(hex: 0x7BD8FF)

        /// Disconnected / unreachable / HTTP-mode banner — muted amber.
        static let amberWarn = SwiftUI.Color(hex: 0xE0A458)

        /// Auth / validation errors — high-contrast crimson.
        static let crimsonError = SwiftUI.Color(hex: 0xFF4D6D)

        // ── Text ────────────────────────────────────────────────────────────

        /// Primary text on dark backgrounds.
        static let textPrimary = SwiftUI.Color(hex: 0xE8EAED)

        /// Secondary / subdued text.
        static let textSecondary = SwiftUI.Color(hex: 0x8B949E)

        /// Disabled / placeholder text.
        static let textDisabled = SwiftUI.Color(hex: 0x484F58)

        // ── Surfaces ────────────────────────────────────────────────────────

        /// Outline / divider on dark surfaces.
        static let outline = SwiftUI.Color(hex: 0x30363D)
    }

    /// Glass surface alpha — use with `Color.glass` for the glassmorphism effect.
    /// Mirrors `BrandTokens.glassSurfaceAlpha`.
    static let glassSurfaceAlpha: CGFloat = 0.06

    // MARK: - Spacing

    /// 2 / 4 / 8 / 16 / 24 / 32 / 48 spacing scale.
    /// Mirrors `Spacing.kt`. Add multiples as needed — avoid ad-hoc values.
    enum Spacing {
        /// 2 pt.
        static let xxs: CGFloat = 2
        /// 4 pt.
        static let xs: CGFloat = 4
        /// 8 pt.
        static let sm: CGFloat = 8
        /// 16 pt.
        static let md: CGFloat = 16
        /// 24 pt.
        static let lg: CGFloat = 24
        /// 32 pt.
        static let xl: CGFloat = 32
        /// 48 pt.
        static let xxl: CGFloat = 48
    }

    // MARK: - Shape

    /// Corner radii for surfaces, cards, sheets, and chips.
    /// Mirrors `Shape.kt` (Material3 `Shapes`). Values are corner radii in points;
    /// shapes are exposed as both raw radii (for `RoundedRectangle`) and
    /// preassembled `RoundedRectangle` instances (for clip / background use).
    enum Shape {
        /// 4 pt — chips, tag pills.
        static let extraSmallRadius: CGFloat = 4
        /// 8 pt — inputs, buttons.
        static let smallRadius: CGFloat = 8
        /// 12 pt — cards, default surfaces.
        static let mediumRadius: CGFloat = 12
        /// 16 pt — modal sheets.
        static let largeRadius: CGFloat = 16
        /// 24 pt — full-bleed prominent surfaces.
        static let extraLargeRadius: CGFloat = 24

        /// 4 pt rounded rectangle — chips, tag pills.
        static let extraSmall = RoundedRectangle(cornerRadius: extraSmallRadius, style: .continuous)
        /// 8 pt rounded rectangle — inputs, buttons.
        static let small = RoundedRectangle(cornerRadius: smallRadius, style: .continuous)
        /// 12 pt rounded rectangle — cards, default surfaces.
        static let medium = RoundedRectangle(cornerRadius: mediumRadius, style: .continuous)
        /// 16 pt rounded rectangle — modal sheets.
        static let large = RoundedRectangle(cornerRadius: largeRadius, style: .continuous)
        /// 24 pt rounded rectangle — full-bleed prominent surfaces.
        static let extraLarge = RoundedRectangle(cornerRadius: extraLargeRadius, style: .continuous)

        /// Default surface shape (alias for `.medium` — cards, default glass surfaces).
        static let card = medium
    }
}
