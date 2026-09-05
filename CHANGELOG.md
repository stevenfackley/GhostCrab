# Changelog

All notable changes to GhostCrab are documented here.  
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Security

- **Bearer token scoped to the gateway origin** — the token is attached only to requests whose host, port and scheme match the configured gateway URL (`OpenClawApiClient.isSameOrigin`). Ktor's `Auth` plugin was replaced by a request hook with no 401-challenge retry, so a redirect off the gateway can never carry the token. The WebSocket path reuses this same client instead of a bare one.
- **Backup disabled** — `allowBackup="false"` plus `dataExtractionRules` / `fullBackupContent` excluding all app data from cloud backup and device-to-device transfer.
- **Release logging** — Ktor header logging is installed only in debug builds; R8 strips `Log.v` / `Log.d` call sites in release (`-assumenosideeffects`).
- **Crash-log scrubbing** also redacts `token=` / `"token":` / `api_key` fields and IPv4 literals, matching the promise in `SECURITY.md`.
- **Token-carrying state can't be logged** — `GatewayConnection.Connected` and `ManualEntryFormState` redact the token in `toString()`.
- **Onboarding token** no longer lives in `rememberSaveable` (saved-instance Bundle); it is held in `OnboardingViewModel`. Copies are flagged `EXTRA_IS_SENSITIVE` so Android 13+ hides the clipboard preview.
- **Cleartext guard** treats IPv6 ULA (`fc00::/7`) and unspecified addresses as private.
- **Host validation** rejects `@`, `?`, `#`, `\` in the manual-entry host field (userinfo / query smuggling into the assembled URL).
- **Gradle wrapper** pins `distributionSha256Sum`.

### Fixed

- `GatewayConnectionManagerImpl.connect` could leave the UI on `Connecting` forever if the handshake failed with a non-`GatewayException` or was cancelled; both now land in a terminal state.
- A gateway that answered `/health` but failed `/status` leaked the freshly built OkHttp client (only `activeClient`, assigned after success, was ever closed).
- WebSocket sessions opened by `ScopeProbeImpl` / `InstalledSkillRepositoryImpl` were never closed (one leaked socket per probe / list / install / uninstall).
- Opening ClawHub with no browser installed crashed with `ActivityNotFoundException`; now shows a toast.
- CodeQL workflow had its `name:` key glued onto a comment line.

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
