import SwiftUI

/// Convenience initializers for `SwiftUI.Color` from packed `0xRRGGBB` hex literals.
///
/// Mirrors the Compose `Color(0xFFRRGGBB)` ergonomic on the Swift side so that
/// `DesignTokens` can stay byte-for-byte faithful to `BrandTokens.kt`.
extension Color {

    /// Builds an opaque `Color` from a 24-bit `0xRRGGBB` value.
    ///
    /// - Parameter hex: Packed RGB value (e.g. `0x5BE9FF`). The alpha channel is
    ///   assumed to be fully opaque (`1.0`).
    init(hex: UInt32) {
        self.init(hex: hex, alpha: 1.0)
    }

    /// Builds a `Color` from a 24-bit `0xRRGGBB` value with an explicit alpha.
    ///
    /// - Parameters:
    ///   - hex: Packed RGB value (e.g. `0xFFFFFF`).
    ///   - alpha: Opacity in the `0.0 ... 1.0` range.
    init(hex: UInt32, alpha: Double) {
        let r = Double((hex >> 16) & 0xFF) / 255.0
        let g = Double((hex >> 8) & 0xFF) / 255.0
        let b = Double(hex & 0xFF) / 255.0
        self.init(.sRGB, red: r, green: g, blue: b, opacity: alpha)
    }
}
