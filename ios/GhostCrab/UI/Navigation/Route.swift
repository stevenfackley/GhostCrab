import Foundation

/// Typed navigation destinations for the GhostCrab iOS app.
///
/// Mirrors the Compose Navigation `Routes` sealed-class on Android. Each case is
/// `Hashable`, so it can be pushed onto a `NavigationPath` and pattern-matched in a
/// `navigationDestination(for:)` block.
///
/// Add a case here whenever a new destination is added — there is intentionally no
/// catch-all route. Compile-time exhaustiveness makes missing destinations a build
/// error rather than a runtime navigation no-op.
public enum Route: Hashable, Sendable {

    /// Entry point — saved profiles + scan/QR/manual entry actions.
    case connectionPicker

    /// Manual host/port/token entry. Optional URL prefill from scan or QR.
    case manualEntry(prefillURL: URL? = nil)

    /// QR code scanner for Cloudflare tunnel pairing or `ghostcrab://pair` deep links.
    case qrScan

    /// Active LAN/Bonjour discovery — shows live mDNS results.
    case scan

    /// First-run onboarding (multi-step pager).
    case onboarding

    /// Connected-session dashboard — gateway status, active model, recent telemetry.
    case dashboard

    /// JSON config editor with diff sheet and ETag-aware updates.
    case configEditor

    /// Model picker — list all models known to the gateway, select an active one.
    case modelManager

    /// AI recommendation flow — prompt the gateway-side AI skill, view suggested config patches.
    case aiRecommendation

    /// Installed-skills management — list, install, uninstall.
    case installedSkills

    /// App settings — security toggles, about, build info.
    case settings
}
