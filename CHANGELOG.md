# Changelog

All notable changes to GhostCrab are documented here.  
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added

- **Release signing config** — `app/build.gradle.kts` reads `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` from `local.properties` first, then process env vars (CI fallback). Unsigned build remains the default when no keystore is configured.
- **Release keystore** — PKCS12, RSA-4096, 30-year validity at `%USERPROFILE%\.android\ghostcrab-release.jks`. Documented in [`docs/RELEASE_SIGNING.md`](docs/RELEASE_SIGNING.md).

---

## [0.1.0] — 2026-04-16

### Added

- **Phase 0–1:** Repo scaffold, Kotlin 2.0/Compose, Koin DI, domain contracts, brand theme (`BrandTokens`, `GhostCrabTheme`), type-safe navigation.
- **Phase 2:** Manual gateway connection, auth probing, encrypted profile storage (AES-256-GCM via Android Keystore).
- **Phase 3:** LAN discovery via `NsdManager` (`_openclaw-gw._tcp.`), scan screen with mDNS pulse animation.
- **Phase 4:** Dashboard with live health polling (30s interval), `SecurityBanner` for HTTP and no-auth gateways, network security config restricting cleartext to private IP ranges.
- **Phase 5:** First-run onboarding walkthrough (7 steps), `CodeBlock` composable, `TroubleshootingDrawer`, cryptographically random token generator.
- **Phase 6:** Config editor — typed form rows (`ToggleRow`, `EnumDropdownRow`, `IntFieldRow`, `StringFieldRow`), ETag-aware PATCH, pending-changes diff sheet.
- **Phase 7:** Model manager — list all models, swap active model with confirmation dialog.
- **Phase 8:** AI recommendations via gateway-proxied CLI — `POST /api/ai/recommend`, `ApplySuggestionsSheet` with per-change toggles.
- **Phase 9:** Settings screen — profile edit/delete, cleartext toggle, onboarding replay, About (version + build SHA).
- **Phase 10:** `PrivacySafeUncaughtExceptionHandler` (redacts tokens/credentials from crash logs), LeakCanary for debug builds, complete ProGuard/R8 rules, release signing from env vars, 4 ADRs.
- **Phase 11:** QR connect via Cloudflare tunnel — `QrScanScreen` (CameraX + ML Kit barcode scanning), `QrScanViewModel`, camera permission handling with permanent-deny dialog, cyan `ViewfinderOverlay`.
- **Phase 11:** `tunnel-qr` Docker helper — Python service reads `cloudflared` stdout for the quick-tunnel URL and serves a QR code page at LAN port 19999 (`docker/tunnel-qr/`).
- **Phase 11:** GhostCrab logo in `ConnectionPickerScreen` TopAppBar; QR hero empty state on first launch.
- **Phase 11:** Animated splash screen — crab `VectorDrawable` with scale+fade `AnimatedVectorDrawable`, branding image on API 31+.
- **Phase 11:** `docs/TUNNEL_SETUP.md` — complete setup guide for Docker Compose and direct install paths.

### Security

- Bearer tokens are never logged. `Authorization` headers stripped from Ktor logs.
- Crash handler sanitizes stack traces before logging.
- Release build: R8 full-mode minification + resource shrinking.
- `CleartextPublicIpInterceptor` enforces network security config at the OkHttp layer.
