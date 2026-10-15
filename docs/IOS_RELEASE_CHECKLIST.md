# iOS / Mac Catalyst Release Checklist

End-to-end walkthrough for the first TestFlight build of GhostCrab. Run through this once; subsequent releases only need the "Per-release" section.

---

## One-time setup

### On the Mac mini (`steve-mac-mini`)

```bash
# 1. Verify Xcode 26+ is selected
xcodebuild -version
xcode-select --print-path   # must be /Applications/Xcode.app/Contents/Developer

# 2. Install xcodegen
brew install xcodegen

# 3. Sign in to Apple ID in Xcode
#    Xcode → Settings → Accounts → add Apple ID → select Team QJW4S8BDFX
#    → Manage Certificates → confirm "Apple Development" + "Apple Distribution" exist
```

### In Apple Developer Portal (https://developer.apple.com/account)

- [x] App ID `com.qavren.ghostcrab` registered (explicit, not wildcard)
- [ ] Team membership active ($99/yr Apple Developer Program)

### In App Store Connect (https://appstoreconnect.apple.com)

- [ ] App record created (Apps → `+` → New App)
  - Platform: iOS
  - Name: `GhostCrab`
  - Primary language: English (U.S.)
  - Bundle ID: `com.qavren.ghostcrab`
  - SKU: `ghostcrab-ios`
  - User Access: Full Access
- [ ] App Information → Privacy Policy URL filled in: `https://getghostcrab.com/privacy` (live as of 2026-08-05)
- [ ] App Information → Primary Category: `Utilities`, Secondary: `Developer Tools`
- [ ] App Information → Content Rights: "Does not contain, show, or access third-party content"
- [ ] App Information → Age Rating: walk through questionnaire, expect **4+**
- [ ] Pricing and Availability → Free, all territories
- [ ] TestFlight tab → Test Information:
  - Beta App Description filled in
  - Beta App Feedback Email: `support@getghostcrab.com`
  - Marketing URL: `https://getghostcrab.com`
  - Privacy Policy URL: same as App Information
- [ ] App Store Connect → Users and Access → Integrations → App Store Connect API:
  - Generate a key with **App Manager** role
  - Save `KeyID`, `IssuerID`, and the `.p8` file (download works once)

### In GitHub repo settings

- [ ] Settings → Secrets and variables → Actions → New repository secret × 3:
  - `ASC_KEY_ID` — the 10-char Key ID
  - `ASC_ISSUER_ID` — the UUID
  - `ASC_KEY_P8` — full contents of the `.p8` file, multi-line

### On getghostcrab.com

- [x] `docs/PRIVACY_POLICY_IOS.md` published at `https://getghostcrab.com/privacy` (site live 2026-08-05; the in-app Settings link resolves to the same URL)

### In the repo (`feat/ios-finish`, PR #127)

- [x] Fonts bundled: Inter (4 weights) and JetBrains Mono (2 weights) in `ios/GhostCrab/Resources/Fonts/`, registered via `UIAppFonts`, OFL licenses alongside
- [x] Privacy manifest: `ios/GhostCrab/Resources/PrivacyInfo.xcprivacy` (no tracking, no collected data, UserDefaults CA92.1), shipped in the bundle
- [x] ATS: `NSAllowsLocalNetworking` only, no `NSAllowsArbitraryLoads` (`docs/adr/0006-ios-ats-local-networking.md`)
- [x] Version: `MARKETING_VERSION` 0.2.0 in `ios/project.yml`, matching Android
- [x] CI lane: `.github/workflows/ios-pr.yml` runs the tests on the iOS simulator and compiles Mac Catalyst on every PR touching `ios/`
- [ ] Release workflow: changed on PR #127 (`ExportOptions.plist` export only with `altool` as the single upload path, validated build number, `rm`-based key wipe, sandbox entitlements kept through xcodegen). First run (2026-10-14, run 38666179693) failed at archive codesign with `errSecInternalComponent` (locked login keychain); `fix/ios-release-pipeline` moved signing to a job keychain. Unverified until a TestFlight run succeeds (roadmap 4.12).

---

## Per-release

### 1. Bump the version (only when shipping a new public version)

Edit `ios/project.yml`:

```yaml
settings:
  base:
    MARKETING_VERSION: "1.0.0"      # ← bump for App Store-visible release
    CURRENT_PROJECT_VERSION: "1"    # CI overrides this with $GITHUB_RUN_NUMBER
```

`MARKETING_VERSION` is shown to users; `CURRENT_PROJECT_VERSION` is internal and **must** monotonically increase per upload (CI handles this).

### 2. Trigger the workflow

**Option A — manual upload (most common during development):**

GitHub → Actions → "iOS + Mac Catalyst Release" → Run workflow → choose `main`. Inputs:
- `build_number`: leave blank (uses run number)
- `skip_macos`: set to `true` to skip the Mac Catalyst leg (saves ~6 min)

**Option B — tagged release:**

```bash
git tag ios-v1.0.0
git push --tags
```

### 3. Watch the workflow

The Mac mini runner does:
1. Checkout
2. `xcodegen generate` → creates `GhostCrab.xcodeproj`
3. Install ASC API key at `~/.appstoreconnect/private_keys/AuthKey_<KeyID>.p8` (mode 600)
4. Create a throwaway keychain (random password; becomes the default and the only user keychain in the search list) and import the team identity "Apple Distribution: Steven Ackley (QJW4S8BDFX)" from the runner's `.p12` (repo variable `IOS_DIST_P12_PATH`, default HaulCall's `~/.haulcall-ci/dist.p12`)
5. **Archive iOS** unsigned (`generic/platform=iOS`, `CODE_SIGNING_ALLOWED=NO`), then ad-hoc sign the archived app with the `project.yml` entitlements (`.github/scripts/ios-entitle-archive.sh`) so export keeps them
6. **Export iOS IPA** with automatic signing via the API key (`-allowProvisioningUpdates`; `destination=export`: export only, no upload)
7. **Verify** the IPA: distribution identity, App Store profile, build number, `get-task-allow` false, keychain group (`.github/scripts/ios-verify-signed-app.sh`)
8. **Upload iOS IPA** via `xcrun altool` → goes to App Store Connect → iOS TestFlight queue
9. **Archive Mac Catalyst** the same way (`generic/platform=macOS,variant=Mac Catalyst`), **export PKG**, stash any Mac installer identity Xcode minted at `~/.ghostcrab-ci/identities.p12`, verify App Sandbox + `network.client` survived
10. **Upload Mac PKG** via `xcrun altool -t macos` → goes to App Store Connect → macOS TestFlight queue
11. Upload artifacts (IPA + PKG + dSYMs) to GitHub
12. Restore the keychain search list and default keychain, delete the job keychain
13. Delete the API key file (`rm -f`; macOS has no `shred`) and fail if it is still there

Total wall-clock: ~12-18 min for both, ~6-8 min iOS-only.

### 4. After upload

- App Store Connect → TestFlight tab → see both builds appear in "Build" lists (one under iOS, one under macOS)
- Apple's processing takes ~5-15 min (sometimes longer for first uploads). Status will move from "Processing" → "Missing Compliance" → "Ready to Test".
- "Missing Compliance" is the encryption export prompt. We set `ITSAppUsesNonExemptEncryption = false` in Info.plist so this should auto-resolve. If it doesn't, click into the build and confirm "Does not use encryption" (only uses standard OS TLS).
- Once "Ready to Test", builds appear in your TestFlight app on iPhone/iPad and macOS.

### 5. Distributing to testers

- TestFlight → Internal Testing → add yourself as a tester. You get the build immediately (no Apple review).
- TestFlight → External Testing → set up a public link if you want broader distribution. **Requires App Review** for each new build's "What to Test" notes (~24h turnaround typically).

---

## Troubleshooting

### `errSecInternalComponent` at CodeSign

codesign reached a private key it could not use: the runner's LaunchAgent gives every job a fresh security session in which the login keychain is locked. The workflow never signs from the login keychain any more; if this comes back, something put the login keychain back in the search list during the job (another signing job on the same Mac running at the same moment), or the identity in the job keychain has no partition list. Re-run once nothing else is signing.

### "No team distribution identity on this runner"

The membership is at Apple's cap of 3 distribution certificates, so CI reuses one instead of minting. Restore the `.p12` (and its `.pw` password file) at `~/.haulcall-ci/dist.p12` on the Mac Mini, or set the `IOS_DIST_P12_PATH` repository variable to another copy of the same identity. Keep it outside `~/Desktop`, `~/Documents` and `~/Downloads` (TCC-protected; a read from the runner blocks).

### "No provisioning profile found"

Xcode's automatic signing occasionally fails on CI. Fix:

```bash
# On the Mac mini, open the generated project once manually to refresh the profile cache:
cd ios && xcodegen generate && open GhostCrab.xcodeproj
# Then close Xcode and re-run the workflow.
```

### "altool: error: Authentication credentials are missing or invalid"

The ASC API key isn't being mounted into the runner's HOME. Verify:

```bash
ls -la ~/.appstoreconnect/private_keys/
# Should show AuthKey_<KeyID>.p8 with mode 600
```

If empty, the workflow's `Install App Store Connect API key` step failed — check that the `ASC_KEY_P8` secret contains the full `-----BEGIN PRIVATE KEY-----` ... `-----END PRIVATE KEY-----` block including line breaks.

### "Mac Catalyst archive failed: app sandbox required for upload"

The entitlements file at `ios/GhostCrab/Resources/GhostCrab.entitlements` must contain `com.apple.security.app-sandbox = true`. We've set this — if it's been edited, restore.

### "Bundle version must be greater than the previously uploaded version"

`CURRENT_PROJECT_VERSION` (the build number) didn't increase. CI uses `$GITHUB_RUN_NUMBER`, which always grows. If you triggered the workflow against a stale branch, re-trigger from `main`.

### "Invalid Bundle Identifier"

The bundle ID `com.qavren.ghostcrab` doesn't match the App Store Connect record. Confirm in the portal and in `ios/project.yml`'s `PRODUCT_BUNDLE_IDENTIFIER`.

---

## Reference

- Bundle ID: `com.qavren.ghostcrab`
- Apple Team ID: `QJW4S8BDFX`
- App display name: `GhostCrab`
- Min iOS: 18.0 (iPhone + iPad)
- Min macOS: 14.0 (Mac Catalyst on Apple Silicon)
- TestFlight runner: `[self-hosted, macOS, ARM64]` (`steve-mac-mini`)
- CI workflow: `.github/workflows/ios-release.yml`
- Privacy policy source: `docs/PRIVACY_POLICY_IOS.md`
- Design spec: `docs/superpowers/specs/2026-05-28-ios-app-design.md`
