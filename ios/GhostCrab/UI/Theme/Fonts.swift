import SwiftUI

/// GhostCrab font ramp: wraps the bundled Inter and JetBrains Mono families.
///
/// Mirrors `Type.kt`. The faces ship as static TTFs in `Resources/Fonts/` and
/// are registered through `UIAppFonts` in `project.yml`. ``PostScriptName``
/// holds the exact names `Font.custom` looks up, and each file is named
/// `<PostScriptName>.ttf`. A missing or renamed file makes SwiftUI fall back
/// to the system font silently; `FontRegistrationTests` guards against that.
///
/// All UI text uses `body`/`bodyMedium`/`bodyBold`/`bodyBlack`. IP addresses,
/// ports, JSON, CLI output, and token fields use `mono`/`monoMedium`.
enum AppFont {

    /// PostScript names of the bundled faces.
    enum PostScriptName {
        static let interRegular = "Inter-Regular"
        static let interMedium = "Inter-Medium"
        static let interSemiBold = "Inter-SemiBold"
        static let interBold = "Inter-Bold"
        static let jetBrainsMonoRegular = "JetBrainsMono-Regular"
        static let jetBrainsMonoMedium = "JetBrainsMono-Medium"

        /// Every bundled face; must match `UIAppFonts` in `project.yml`.
        static let all: [String] = [
            interRegular, interMedium, interSemiBold, interBold,
            jetBrainsMonoRegular, jetBrainsMonoMedium,
        ]
    }

    // MARK: - Inter (UI text)

    /// Inter Regular: body copy, labels, default UI text.
    static func body(_ size: CGFloat) -> Font {
        .custom(PostScriptName.interRegular, size: size)
    }

    /// Inter Medium: subdued titles, label-medium.
    static func bodyMedium(_ size: CGFloat) -> Font {
        .custom(PostScriptName.interMedium, size: size)
    }

    /// Inter SemiBold: headlines, prominent titles.
    static func bodyBold(_ size: CGFloat) -> Font {
        .custom(PostScriptName.interSemiBold, size: size)
    }

    /// Inter Bold: display-scale text.
    static func bodyBlack(_ size: CGFloat) -> Font {
        .custom(PostScriptName.interBold, size: size)
    }

    // MARK: - JetBrains Mono (code, IPs, JSON, tokens)

    /// JetBrains Mono Regular: IP addresses, ports, JSON, CLI output.
    static func mono(_ size: CGFloat) -> Font {
        .custom(PostScriptName.jetBrainsMonoRegular, size: size)
    }

    /// JetBrains Mono Medium: emphasised code tokens.
    static func monoMedium(_ size: CGFloat) -> Font {
        .custom(PostScriptName.jetBrainsMonoMedium, size: size)
    }
}
