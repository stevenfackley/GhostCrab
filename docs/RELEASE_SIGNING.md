# Release Signing

How GhostCrab release builds are signed.

## Keystore

**Location:** `C:\Users\steve\.android\ghostcrab-release.jks`

- Format: **PKCS12**
- Algorithm: **RSA 4096**
- Validity: **30 years** (Play Store requires ≥25 years past Oct 2033)
- Alias: `ghostcrab-release`
- Created: 2026-04-17

PKCS12 uses a single password for both the store and the key — `KEYSTORE_PASSWORD` and `KEY_PASSWORD` are identical.

> **Credentials live in `local.properties`** (git-ignored).
> See that file for `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

---

## How the build consumes credentials

`app/build.gradle.kts` resolves each credential in this order:

1. `local.properties` (local dev)
2. Process env var of the same name (CI)

Both mechanisms share identical keys: `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

If `KEYSTORE_PATH` is absent from both, the release build produces an **unsigned** APK (`app-release-unsigned.apk`). This is the fallback so CI smoke-build jobs that don't need to sign still succeed.

---

## Building a signed release

```powershell
./gradlew :app:assembleRelease --no-configuration-cache
```

Output: `app/build/outputs/apk/release/app-release.apk` (signed, v2 scheme).

Verify:

```powershell
& "D:\.android\build-tools\35.0.0\apksigner.bat" verify --verbose `
    app\build\outputs\apk\release\app-release.apk
```

Install on device:

```powershell
adb install -r app\build\outputs\apk\release\app-release.apk
```

---

## Backing up the keystore

**Do this now, and every time you rotate the key.** If you lose this keystore and have not enrolled in Play App Signing, you cannot update the app on Play Store.

Recommended: copy to at least two of
- Password manager file attachment (1Password, Bitwarden, etc.)
- Encrypted cloud backup (rclone + age, or similar)
- Hardware token / offline medium

Backup checklist:
- [ ] `ghostcrab-release.jks` file
- [ ] Store password (separate from the file)
- [ ] Key alias (`ghostcrab-release`)

---

## Play App Signing (recommended before first upload)

Enroll the app in **Play App Signing** the first time you upload an AAB. Google then:
- Holds the real *app-signing* key (which signs what end users install)
- Treats your current keystore as the *upload* key (replaceable if lost)

This removes the existential risk of losing your local keystore. Do this before shipping v1.0.

---

## CI signing

In GitHub Actions, inject credentials as secrets:

```yaml
- name: Decode keystore
  run: |
    echo "${{ secrets.KEYSTORE_BASE64 }}" | base64 -d > $HOME/ghostcrab-release.jks

- name: Build signed release
  env:
    KEYSTORE_PATH: ${{ github.workspace }}/../ghostcrab-release.jks
    KEYSTORE_PASSWORD: ${{ secrets.KEYSTORE_PASSWORD }}
    KEY_ALIAS: ghostcrab-release
    KEY_PASSWORD: ${{ secrets.KEYSTORE_PASSWORD }}
  run: ./gradlew :app:bundleRelease --no-configuration-cache
```

To generate `KEYSTORE_BASE64` for the secret:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\.android\ghostcrab-release.jks")) `
    | Set-Clipboard
```

---

## Regenerating the keystore

Only do this if you are starting fresh and have not yet uploaded to Play Store with this key. Once an app is uploaded, the signing key cannot change without going through Play App Signing key rotation.

```powershell
& "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" `
    -genkeypair -v `
    -keystore "$env:USERPROFILE\.android\ghostcrab-release.jks" `
    -storetype PKCS12 -keyalg RSA -keysize 4096 -validity 10950 `
    -alias ghostcrab-release `
    -dname "CN=GhostCrab, OU=OpenClaw, O=OpenClaw, L=Unknown, ST=Unknown, C=US"
```

Then update `local.properties` with the new password.
