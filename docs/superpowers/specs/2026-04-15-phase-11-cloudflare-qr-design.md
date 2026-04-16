# Phase 11 Design: Cloudflare Tunnel + QR Connect + Logo + Animated Splash

**Date:** 2026-04-15  
**Status:** Approved  
**Scope:** GhostCrab Android app (Phase 11) + `tunnel-qr` gateway helper tooling

---

## Problem

mDNS auto-discovery breaks on mesh networks (AP isolation, multicast suppression, subnet splits). Users need an alternative way to connect to their OpenClaw Gateway without port-forwarding their router. The first-launch UX also lacks brand identity — no logo, no animation.

---

## Solution Overview

Three features shipped together as Phase 11:

1. **Cloudflare Tunnel + QR Connect** — a `tunnel-qr` helper runs alongside the gateway, extracts the cloudflared quick-tunnel URL, and serves a QR code page at LAN port 19999. GhostCrab gains a QR scanner that reads the URL and drops it into the existing connection flow.
2. **Logo on home screen** — replace the text-only `TopAppBar` title with `logo_cropped.png` (crab icon + wordmark). QR scan icon lives in the toolbar actions slot.
3. **Animated splash screen** — wire `windowSplashScreenAnimatedIcon` to an `AnimatedVectorDrawable` of the crab silhouette (scale-in + cyan glow, 600ms).

---

## Architecture

### How it fits the existing layers

No domain interface changes. All additions are at the UI layer (new screen + ViewModel) and gateway tooling (outside the app).

```
ConnectionPickerScreen  →  QrScanScreen (new)
                               ↓
                        QrScanViewModel (new)
                               ↓
                        validates URL string
                               ↓
                        navigates to ManualEntryScreen (existing)
                        with URL pre-filled
```

The scan result is just a string. `QrScanViewModel` validates it is a valid `http://` or `https://` URL and hands off to the existing `ManualEntryViewModel` flow — no new repository or domain code needed.

---

## Part 1: `tunnel-qr` Gateway Helper

### What it does

- Polls cloudflared's metrics API at `http://localhost:2000/metrics` every 2s
- Parses the `tunnel_id` / hostname once it appears (takes ~5–10s after cloudflared starts)
- Serves two endpoints:
  - `GET /` — HTML page with a QR code image and the URL as text
  - `GET /url` — plain-text tunnel URL (for scripting / named-tunnel upgrade)
- Listens on `0.0.0.0:19999` (LAN-accessible, never exposed through cloudflared)

### Deployment — Docker Compose (recommended)

```yaml
# Add to existing docker-compose.yml
services:
  cloudflared:
    image: cloudflare/cloudflared:latest
    restart: unless-stopped
    command: >-
      tunnel --url http://<gateway-service>:18789
      --no-autoupdate --metrics 0.0.0.0:2000
    networks:
      - internal

  tunnel-qr:
    image: ghcr.io/openclaw/tunnel-qr:latest
    restart: unless-stopped
    ports:
      - "19999:19999"
    environment:
      CLOUDFLARED_METRICS: http://cloudflared:2000
    depends_on:
      - cloudflared
    networks:
      - internal
```

### Deployment — Direct Install

Same Python code, three delivery formats:

| Format | Command |
|--------|---------|
| PyPI | `pip install openclaw-tunnel-qr && openclaw-tunnel-qr` |
| Shell script | `curl -fsSL openclaw.io/tunnel-qr.sh \| sh` |
| Docker standalone | `docker run -p 19999:19999 ghcr.io/openclaw/tunnel-qr` |

For systemd: `docs/TUNNEL_SETUP.md` includes a ready-to-use unit file.

### Source location (this repo)

```
docker/
  tunnel-qr/
    Dockerfile          # FROM python:3.12-alpine, installs qrcode[pil] + requests
    serve.py            # ~100 lines: poll metrics, extract URL, serve HTML+QR
  docker-compose.example.yml   # drop-in snippet for both services
docs/
  TUNNEL_SETUP.md       # install guide: Docker path + direct install path (Linux/macOS/Windows)
```

### Named tunnel upgrade path

When the user sets `CLOUDFLARED_TUNNEL_TOKEN` env var, cloudflared uses a named (permanent) tunnel instead of a quick tunnel. `tunnel-qr` works identically — the QR page just shows a permanent URL. User scans once, saved forever.

---

## Part 2: GhostCrab App Changes

### 2.1 New files

| File | Purpose |
|------|---------|
| `ui/connection/QrScanScreen.kt` | Full-screen CameraX viewfinder with cyan corner-bracket overlay and scan-line animation |
| `ui/connection/QrScanViewModel.kt` | Manages scan state, validates decoded URL, emits navigation event |
| `res/drawable/ic_splash_crab.xml` | VectorDrawable — simplified crab silhouette traced from `logo_cropped.png` |
| `res/animator/splash_crab_anim.xml` | ObjectAnimator: scale 0.7→1.0 + alpha 0→1, 600ms, `FastOutSlowIn` interpolator |
| `res/drawable/animated_splash_crab.xml` | AnimatedVectorDrawable wrapping above two files |
| `res/drawable-nodpi/logo_ghostcrab.png` | Copy of `logo_cropped.png` from repo root — referenced as `@drawable/logo_ghostcrab` |
| `docker/tunnel-qr/Dockerfile` | tunnel-qr container image |
| `docker/tunnel-qr/serve.py` | tunnel-qr server logic |
| `docker/docker-compose.example.yml` | Example compose snippet |
| `docs/TUNNEL_SETUP.md` | Gateway setup guide |

### 2.2 Modified files

| File | Change |
|------|--------|
| `ConnectionPickerScreen.kt` | TopAppBar: replace text title with `logo_ghostcrab.png` via Coil; add QR scan `IconButton` in actions; empty-state: QR hero card (large scan prompt) + secondary LAN/Manual buttons |
| `ConnectionPickerViewModel.kt` | Expose `val hasProfiles: StateFlow<Boolean>` derived from `profiles` |
| `NavGraph.kt` | Add `Routes.QrScan` route; `QrScanScreen` navigates to `ManualEntry` passing URL as nav arg |
| `Routes.kt` | Add `QrScan` object + `ManualEntry(prefillUrl: String?)` with optional arg |
| `app/build.gradle.kts` | Add CameraX (`camera-camera2`, `camera-lifecycle`, `camera-view` 1.4.x) + ML Kit (`barcode-scanning:17.3.x`) |
| `AndroidManifest.xml` | Add `<uses-permission android:name="android.permission.CAMERA" />` + `<uses-feature android:name="android.hardware.camera" android:required="false" />` |
| `themes.xml` | `windowSplashScreenAnimatedIcon` → `animated_splash_crab`; `windowSplashScreenAnimationDuration` → 600 |
| `res/values-v31/themes.xml` (new) | `windowSplashScreenBrandingImage` → `@drawable/logo_ghostcrab` — API 31+ only; keeps base themes.xml clean |
| `gradle/libs.versions.toml` | Add camerax, mlkit-barcode version aliases |

### 2.3 QR scan flow (step by step)

1. User taps **QR icon** in toolbar (or **Open Camera** in empty-state hero card)
2. `QrScanViewModel.onScanRequested()` checks camera permission
   - If not granted: `rememberLauncherForActivityResult(RequestPermission)` fires → system dialog
   - If denied permanently: show `AlertDialog` with explanation + "Open Settings" button; no silent failure
3. CameraX `PreviewView` fills the screen; `ImageAnalysis` use-case runs ML Kit barcode analyzer on each frame
4. On first valid QR decode: `QrScanViewModel.onQrDecoded(rawValue)` runs validation:
   - Must start with `http://` or `https://`
   - Must parse as a valid URI
   - If invalid: snackbar "QR code doesn't contain a valid gateway URL" — camera stays open
5. Valid URL → `NavController.navigate(Routes.ManualEntry(prefillUrl = url))` — camera stops
6. `ManualEntryScreen` receives `prefillUrl`, passes to `ManualEntryViewModel.setPrefillUrl()` — URL field pre-populated
7. User adds token if needed, taps Connect → existing `probeAuth` + `connect` flow

### 2.4 Connection Picker screen states

**No saved profiles (empty state):**
- Large QR hero card: crab icon, "Scan QR to connect", instructional copy pointing to `http://<gateway-ip>:19999`, cyan "Open Camera" button
- Two secondary buttons below: "Scan LAN" and "Manual Entry"

**Has saved profiles:**
- Logo in `TopAppBar` (left side: crab icon + wordmark via Coil)
- Two `IconButton`s in actions slot: QR scan icon + Add (+) icon
- Profile list as before

### 2.5 Animated splash screen

- `ic_splash_crab.xml`: VectorDrawable using path data traced from the crab in `logo_cropped.png`. Simplified to ~4–6 paths. Fill color: `#5BE9FF` (colorCyanPrimary).
- `splash_crab_anim.xml`: Two `objectAnimator`s on the `<group>` wrapping all paths — `scaleX`/`scaleY` from 0.7→1.0 and `alpha` from 0.0→1.0, both 600ms, `@android:interpolator/fast_out_slow_in`
- `animated_splash_crab.xml`: `<animated-vector>` referencing both
- `themes.xml` update (base, all API levels):
  ```xml
  <item name="windowSplashScreenAnimatedIcon">@drawable/animated_splash_crab</item>
  <item name="windowSplashScreenAnimationDuration">600</item>
  ```
- `res/values-v31/themes.xml` (new file, API 31+ override):
  ```xml
  <style name="Theme.GhostCrab" parent="Theme.SplashScreen">
      <item name="windowSplashScreenBrandingImage">@drawable/logo_ghostcrab</item>
  </style>
  ```

### 2.6 New dependencies

```kotlin
// CameraX
implementation("androidx.camera:camera-camera2:1.4.1")
implementation("androidx.camera:camera-lifecycle:1.4.1")
implementation("androidx.camera:camera-view:1.4.1")

// ML Kit barcode scanning (QR decode — no network call, on-device)
implementation("com.google.mlkit:barcode-scanning:17.3.0")
```

---

## Error Handling

| Scenario | Behavior |
|----------|----------|
| Camera permission denied (first time) | System dialog via `RequestPermission` launcher |
| Camera permission permanently denied | `AlertDialog` with explanation + "Open Settings" deep link |
| QR decoded but not a URL | Snackbar "Doesn't contain a valid gateway URL" — camera stays open |
| QR is a URL but gateway unreachable | Handled by existing `ManualEntryViewModel` error flow after connect attempt |
| cloudflared not yet ready (tunnel-qr polled too early) | `tunnel-qr` shows "Waiting for tunnel..." spinner on the QR page — user refreshes browser |
| tunnel-qr container not running | User falls back to Manual Entry (existing flow unchanged) |

---

## Out of Scope (Phase 11)

- Named tunnel setup UI inside the app (user sets `CLOUDFLARED_TUNNEL_TOKEN` in their compose file manually)
- QR code containing auth token (security risk — tokens added in-app after scan)
- iOS
- NFC tap-to-connect
- Deep links / Android App Links for the tunnel URL

---

## Acceptance Criteria

- [ ] `tunnel-qr` Docker image builds and serves a QR page within 15s of cloudflared startup
- [ ] `openclaw-tunnel-qr` pip package runnable standalone on Linux/macOS
- [ ] `docs/TUNNEL_SETUP.md` covers both Docker and direct install
- [ ] GhostCrab: scanning a valid QR navigates to ManualEntry with URL prefilled
- [ ] GhostCrab: scanning a non-URL QR shows snackbar, camera stays open
- [ ] GhostCrab: camera permission permanently denied shows explanation dialog + Settings link
- [ ] GhostCrab: Connection Picker empty state shows QR hero card
- [ ] GhostCrab: Connection Picker with profiles shows logo in TopAppBar + QR icon
- [ ] GhostCrab: animated splash plays on first launch (600ms scale+glow)
- [ ] `./gradlew assembleDebug` succeeds with new dependencies
- [ ] `./gradlew testDebugUnitTest` passes (QrScanViewModel unit tests: valid URL, invalid URL, permission denied state)
