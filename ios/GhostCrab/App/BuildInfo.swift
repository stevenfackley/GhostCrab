import Foundation

/// Build-time metadata surfaced by the Settings → About section.
///
/// `gitSHA` is overwritten by a build-phase script during CI:
/// see `ios/Scripts/inject-build-info.sh`. In development builds without
/// that script wired up, the placeholder `"dev"` is shown — which is
/// distinct from any real SHA prefix and therefore obvious in screenshots.
public enum BuildInfo {

    /// Short Git SHA of the commit this binary was built from.
    /// Format: 7-char prefix, e.g. `"d4f439d"`. Overwritten at build time.
    public static let gitSHA: String = "dev"

    /// `CFBundleShortVersionString` from Info.plist — matches `MARKETING_VERSION` in project.yml.
    public static var marketingVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0.0.0"
    }

    /// `CFBundleVersion` from Info.plist — the monotonic build number (= CI run number).
    public static var buildNumber: String {
        Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "0"
    }

    /// Single human-readable version line, e.g. `"1.0.0 (42) · d4f439d"`.
    public static var displayVersion: String {
        "\(marketingVersion) (\(buildNumber)) · \(gitSHA)"
    }
}
