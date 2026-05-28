import SwiftUI

/// GhostCrab font ramp — wraps the bundled Inter and JetBrains Mono families.
///
/// Mirrors `Type.kt`. The Android side requests four Inter weights (Regular,
/// Medium, SemiBold, Bold) and two JetBrains Mono weights (Regular, Medium) via
/// the Google Fonts provider. We surface the same set here as `.custom(_:size:)`
/// lookups. PostScript names match the standard release artifacts on Google
/// Fonts (used unmodified when the `.ttf` files are added to the Xcode asset
/// bundle).
///
/// All UI text uses `body`/`bodyMedium`/`bodyBold`/`bodyBlack`. IP addresses,
/// ports, JSON, CLI output, and token fields use `mono`/`monoMedium`.
enum AppFont {

    // MARK: - Inter (UI text)

    /// Inter Regular — body copy, labels, default UI text.
    static func body(_ size: CGFloat) -> Font {
        .custom("Inter-Regular", size: size)
    }

    /// Inter Medium — subdued titles, label-medium.
    static func bodyMedium(_ size: CGFloat) -> Font {
        .custom("Inter-Medium", size: size)
    }

    /// Inter SemiBold — headlines, prominent titles.
    static func bodyBold(_ size: CGFloat) -> Font {
        .custom("Inter-SemiBold", size: size)
    }

    /// Inter Bold — display-scale text.
    static func bodyBlack(_ size: CGFloat) -> Font {
        .custom("Inter-Bold", size: size)
    }

    // MARK: - JetBrains Mono (code, IPs, JSON, tokens)

    /// JetBrains Mono Regular — IP addresses, ports, JSON, CLI output.
    static func mono(_ size: CGFloat) -> Font {
        .custom("JetBrainsMono-Regular", size: size)
    }

    /// JetBrains Mono Medium — emphasised code tokens.
    static func monoMedium(_ size: CGFloat) -> Font {
        .custom("JetBrainsMono-Medium", size: size)
    }
}
