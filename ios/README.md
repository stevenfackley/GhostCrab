# GhostCrab iOS

Native iOS client for OpenClaw Gateway. Pure SwiftUI, iOS 18+.

See the full design at `docs/superpowers/specs/2026-05-28-ios-app-design.md`.

## Identifiers

| | Value |
|---|---|
| Bundle ID | `com.qavren.ghostcrab` (shared between iOS and Mac Catalyst) |
| Apple Team ID | `QJW4S8BDFX` |
| Display name | `GhostCrab` |
| Min iOS | 18.0 |
| Min macOS (Catalyst) | 14.0 (Sonoma) |
| Platforms | iPhone + iPad + Mac (Catalyst) |

## First-time setup on the Mac mini

1. Install Xcode 26+ from the App Store. Confirm: `xcodebuild -version`.
2. Sign in with Apple ID in Xcode → Settings → Accounts → select team `QJW4S8BDFX` → Manage Certificates → ensure Apple Development + Apple Distribution exist.
3. Install xcodegen: `brew install xcodegen`.
4. From `ios/`: `xcodegen generate` → creates `GhostCrab.xcodeproj`.
5. Open `GhostCrab.xcodeproj` in Xcode. Signing & Capabilities → automatic, team `QJW4S8BDFX`.

## Build commands

```bash
# Generate / regenerate the Xcode project (after editing project.yml)
cd ios && xcodegen generate

# Local debug build to attached device
xcodebuild -project ios/GhostCrab.xcodeproj -scheme GhostCrab \
  -destination "platform=iOS,name=<your-device>" build

# Run unit tests
xcodebuild -project ios/GhostCrab.xcodeproj -scheme GhostCrab \
  -destination "platform=iOS Simulator,name=iPhone 16" test

# Archive for TestFlight (CI does this automatically — manual only for debugging)
xcodebuild -project ios/GhostCrab.xcodeproj -scheme GhostCrab \
  -configuration Release -archivePath build/GhostCrab.xcarchive \
  -destination "generic/platform=iOS" archive
```

## CI

`.github/workflows/ios-release.yml` runs on `[self-hosted, macOS, ARM64]` (the Mac mini), triggered by:

- `workflow_dispatch` — manual TestFlight upload (`skip_macos` input available to skip the Mac Catalyst leg)
- Push tag matching `ios-v*` — versioned releases (e.g. `git tag ios-v1.0.0 && git push --tags`)

Each run archives + uploads **two** TestFlight builds under the same `com.qavren.ghostcrab` App Store Connect record:

- iOS `.ipa` (`-t ios`) — appears in the iOS TestFlight app on iPhone/iPad
- Mac Catalyst `.pkg` (`-t macos`) — appears in the macOS TestFlight app on the Mac mini

Required GitHub repo secrets: `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_P8`.

## Folder layout

```
ios/
├── project.yml                 # xcodegen project definition
├── ExportOptions.plist         # App Store upload options
├── GhostCrab/
│   ├── App/                    # @main, AppContainer, RootView
│   ├── Domain/{Model,Error,Repository,Util}/
│   ├── Data/{Api,Discovery,Storage,Impl}/
│   ├── UI/{Components,Theme,Navigation,Onboarding,Connection,Dashboard,Config,Models,AI,Settings}/
│   ├── Crash/
│   └── Resources/              # Info.plist, Assets.xcassets, Fonts/
├── GhostCrabTests/             # Swift Testing
└── GhostCrabUITests/           # XCUITest
```

Mirrors `app/src/main/kotlin/com/openclaw/ghostcrab/` near 1:1.
