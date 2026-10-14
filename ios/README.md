# GhostCrab iOS

Native iOS client for OpenClaw Gateway. Pure SwiftUI, iOS 18+.

See the full design at `docs/superpowers/specs/2026-05-28-ios-app-design.md`.

## Identifiers

| | Value |
|---|---|
| Bundle ID | `com.qavren.ghostcrab` (shared between iOS and Mac Catalyst) |
| Apple Team ID | `QJW4S8BDFX` |
| Display name | `GhostCrab` |
| Version | `MARKETING_VERSION` 0.2.0 (mirrors Android `versionName`); the build number is CI's run number |
| Min iOS | 18.0 |
| Min macOS (Catalyst) | 14.0 (Sonoma) |
| Platforms | iPhone + iPad + Mac (Catalyst) |

## First-time setup on the Mac mini

1. Install Xcode 26+ from the App Store (the CI runner is on Xcode 27). Confirm: `xcodebuild -version`.
2. Sign in with Apple ID in Xcode → Settings → Accounts → select team `QJW4S8BDFX` → Manage Certificates → ensure Apple Development + Apple Distribution exist.
3. Install xcodegen: `brew install xcodegen`.
4. From `ios/`: `xcodegen generate` → creates `GhostCrab.xcodeproj` (generated, not tracked).
5. Open `GhostCrab.xcodeproj` in Xcode. Signing & Capabilities → automatic, team `QJW4S8BDFX`.

## Build commands

```bash
# Generate / regenerate the Xcode project (after editing project.yml or adding files)
cd ios && xcodegen generate

# Local debug build to attached device
xcodebuild -project ios/GhostCrab.xcodeproj -scheme GhostCrab \
  -destination "platform=iOS,name=<your-device>" build

# Mac Catalyst compile check (what the PR lane runs; no signing)
xcodebuild -project ios/GhostCrab.xcodeproj -scheme GhostCrab \
  -destination "platform=macOS,variant=Mac Catalyst,arch=arm64" \
  CODE_SIGNING_ALLOWED=NO CODE_SIGN_IDENTITY="" build

# Archive for TestFlight (CI does this automatically — manual only for debugging)
xcodebuild -project ios/GhostCrab.xcodeproj -scheme GhostCrab \
  -configuration Release -archivePath build/GhostCrab.xcarchive \
  -destination "generic/platform=iOS" archive
```

Every build rewrites `GhostCrab/App/BuildInfo.swift` with the current git SHA (`Scripts/inject-build-info.sh`). Don't commit that change: `git checkout -- ios/GhostCrab/App/BuildInfo.swift`.

## Running tests on the Mac

The `GhostCrab` scheme's test action runs `GhostCrabTests` (Swift Testing unit suites, using the fakes in `GhostCrabTests/Support/`) and `GhostCrabUITests` (one XCUITest launch smoke test).

```bash
cd ios && xcodegen generate

# Pick a simulator name from the installed runtime
xcrun simctl list devices available | grep iPhone

# Whole suite
xcodebuild test -project GhostCrab.xcodeproj -scheme GhostCrab \
  -destination "platform=iOS Simulator,name=iPhone 17"

# One suite
xcodebuild test -project GhostCrab.xcodeproj -scheme GhostCrab \
  -destination "platform=iOS Simulator,name=iPhone 17" \
  -only-testing:GhostCrabTests/PairingLinkTests

git checkout -- GhostCrab/App/BuildInfo.swift
```

## CI

### PR lane: `.github/workflows/ios-pr.yml`

Runs on every pull request that touches `ios/**` or the workflow file, on the self-hosted Mac mini runner `[self-hosted, macOS, ARM64]`. Fork PRs are skipped, and checkout does not persist the token. Two jobs:

- `ios-pr`: `xcodegen generate`, then `xcodebuild test` on an iPhone simulator. The simulator is the first iPhone that `xcodebuild -showdestinations` lists for the scheme (falling back to `xcrun simctl list`), so an Xcode upgrade needs no workflow edit. The xcresult test summary prints on every run; on failure, crash reports print and the xcresult is uploaded.
- `ios-pr-catalyst`: an unsigned `xcodebuild build` for `platform=macOS,variant=Mac Catalyst,arch=arm64`, so iOS-only APIs fail the PR instead of the release archive.

### Release: `.github/workflows/ios-release.yml`

Runs on the same runner, triggered by:

- `workflow_dispatch` — manual TestFlight upload (`skip_macos` input available to skip the Mac Catalyst leg)
- Push tag matching `ios-v*` — versioned releases (e.g. `git tag ios-v1.0.0 && git push --tags`)

Each run archives, exports and uploads **two** TestFlight builds under the same `com.qavren.ghostcrab` App Store Connect record:

- iOS `.ipa` (`-t ios`) — appears in the iOS TestFlight app on iPhone/iPad
- Mac Catalyst `.pkg` (`-t macos`) — appears in the macOS TestFlight app on the Mac mini

`ExportOptions.plist` only exports (`destination=export`); `xcrun altool` with the App Store Connect API key is the single upload path. The final step deletes the API key file and fails if it is still there.

Required GitHub repo secrets: `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_P8`. Setup steps: `docs/IOS_RELEASE_CHECKLIST.md`.

## Fonts

Inter (Regular, Medium, SemiBold, Bold) and JetBrains Mono (Regular, Medium) are bundled as static TTFs in `GhostCrab/Resources/Fonts/`, with their OFL licenses (`LICENSE-Inter.txt`, `LICENSE-JetBrainsMono.txt`). `project.yml` registers them under `UIAppFonts`; each file is named after its PostScript name (`AppFont.PostScriptName` in `UI/Theme/Fonts.swift`). `FontRegistrationTests` fails if any face falls back to the system font.

## Pairing links and deep links

A pairing link is `ghostcrab://pair?p=<base64url JSON>`; the v1 payload carries the gateway URL, an optional token and an optional name. Three ways in:

- **QR scan** (iPhone/iPad): the scanner decodes the code with `PairingLink`.
- **Deep link**: the `ghostcrab` URL scheme is registered (`CFBundleURLTypes`), so the OS camera, Safari and other apps open the app; `RootView.onOpenURL` hands the link to `PairingDeepLink`.
- **Paste**: Manual Entry's Pairing link field takes a link or a plain gateway URL. On Mac Catalyst, which has no QR scanner, this is the way to pair.

All three only prefill Manual Entry; nothing connects until the user taps Connect. The token goes into `PairingHandoff`, an in-memory one-shot stash scoped to the gateway's origin that expires after two minutes. It never enters `Route` or the navigation path, and a prefill for a different host drops it. Links over 4096 characters, non-v1 payloads, non-`http(s)` URLs, userinfo and out-of-range ports are rejected, and error text never echoes the payload.

## App Transport Security limits

`Info.plist` declares `NSAllowsLocalNetworking` only, never `NSAllowsArbitraryLoads` (see `docs/adr/0006-ios-ats-local-networking.md`). Plain HTTP therefore works only to IP addresses, unqualified host names (`http://nas:18789`) and `.local` names. Any other host name (public DNS, Tailscale `*.ts.net`, `.lan`, `home.arpa`) needs HTTPS. Manual Entry warns before connecting when ATS will block the host. The Settings toggle "Allow HTTP to public IPs" only covers public IP literals (`CleartextPublicIPGuard`) and cannot override ATS.

## Privacy manifest

`GhostCrab/Resources/PrivacyInfo.xcprivacy` declares no tracking, no collected data and one required-reason API: UserDefaults (CA92.1), used by `SettingsStore` and `ProfileStore`. `PrivacyManifestTests` checks that it ships in the app bundle.

## Folder layout

```
ios/
├── project.yml                 # xcodegen project definition
├── ExportOptions.plist         # App Store export options (export only; altool uploads)
├── Scripts/                    # inject-build-info.sh (git SHA into BuildInfo.swift)
├── GhostCrab/
│   ├── App/                    # @main, AppContainer, RootView
│   ├── Domain/{Model,Error,Repository,Util}/
│   ├── Data/{Api,Discovery,Storage,Impl}/
│   ├── UI/{Components,Theme,Navigation,Onboarding,Connection,Dashboard,Config,Models,AI,Settings,InstalledSkills}/
│   ├── Crash/
│   └── Resources/              # Info.plist, Assets.xcassets, Fonts/, PrivacyInfo.xcprivacy, entitlements
├── GhostCrabTests/             # Swift Testing; Support/ holds fakes and MockURLProtocol
└── GhostCrabUITests/           # XCUITest launch smoke test
```

Mirrors `app/src/main/kotlin/com/openclaw/ghostcrab/` near 1:1.
