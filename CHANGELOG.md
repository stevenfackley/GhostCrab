# Changelog

All notable changes to GhostCrab are documented here.  
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

## [0.2.0] - 2026-10-14

### Added

- **Pairing links** — `ghostcrab://pair` deep links and QR codes prefill the manual connect form; you still tap Connect. The token is held in memory only, for at most 2 minutes, and links re-delivered from Recents are ignored.
- **IPv6-safe gateway URLs** — one `GatewayUrl` parser (port range check, zone-id stripping) now used by manual entry and discovery; mDNS records that cannot form a valid URL are skipped.
- **Single-leaf config editing** — the config editor edits individual gateway leaves and falls back to raw JSON when a section cannot be decoded.
- **Error messages** show the URL, HTTP status and exception class everywhere.
- **Lifecycle-aware UI** — screens collect state only while visible and one-shot events are buffered, so rotation no longer drops navigation events.
- **Tests** — settings/onboarding repositories, the Koin module graph, WebSocket timeouts and the config ViewModel are covered (401 unit tests).
- **Release signing config** — `app/build.gradle.kts` reads `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` from `local.properties` first, then env vars (CI fallback); unsigned remains the default. See [`docs/RELEASE_SIGNING.md`](docs/RELEASE_SIGNING.md).

### Changed

- The AI recommendations entry point and route are hidden unless `AI_PRO_ENABLED` is set.
- AI suggestions are no longer pre-selected and are applied as one merge-patch per config section.
- The dashboard polls `/health` on the live session client.
- WebSocket requests time out after 30 s and fail at once when the socket closes; sockets open through the session client.
- Cleartext blocks and protocol errors are reported as such instead of "unreachable"; a disconnect race raises `GatewayNotConnectedException` instead of crashing.
- The config editor refreshes its ETag after each write; a failed reload after a successful write is a success with a warning.
- Internal: large screens split into focused files, IO dispatcher injected via Koin, dead code removed, detekt baseline trimmed, lint warnings wired up and unused resources fail the build.

### Fixed

- Cancelled coroutines no longer show up as errors (`suspendRunCatching` rethrows `CancellationException`), and a profile-save failure after a successful connect no longer crashes the app.
- Saving or reloading one config section (including after a 412) no longer drops pending edits in other sections.
- Undecodable saved profiles are never overwritten; settings read errors fail closed.
- A reconnect drops the config ETag, so a stale ETag is never sent to a different gateway.
- Clear-all also wipes undecodable profiles and their corruption backup.
- QR scanning releases the camera and navigates once per scan.
- Malformed gateway skill JSON produces typed errors instead of an NPE; a skill install that cannot open a session reports a network failure.
- Editing a tokenless profile, or reconnecting a known gateway, no longer fails on the token read; `hasToken` stays in sync after an edit.
- `GatewayConnectionManagerImpl.connect` could stay on `Connecting` forever after a non-gateway failure or cancellation.
- Leaked OkHttp client after a failed `/status`, and WebSocket sessions never closed by the scope probe and skill repository.
- Opening ClawHub with no browser installed crashed; it now shows a toast.
- CodeQL workflow had its `name:` key glued onto a comment line.

### Security

- **Secrets are redacted before AI** — tokens and API keys are stripped from the config sent as AI context, and redacted placeholders can never be applied back.
- **Security-affecting AI suggestions need confirmation** — they are withheld until you confirm them in a dialog, and null deletions are confirmed too.
- **Tokens are masked and redacted** in the config and settings UI; token-carrying state redacts the token in `toString()`.
- **Cleartext opt-in is live** — toggling it takes effect on the current session; the setting path in error text is correct.
- **Pairing token never persisted** by the handoff, never put in a navigation route or saved state; payloads over 4096 characters are rejected.
- **Bearer token scoped to the gateway origin** — attached only to requests matching the configured host, port and scheme, with no 401-challenge retry, so a redirect can never carry it. The WebSocket path uses the same client.
- **Backup disabled** — `allowBackup="false"` plus `dataExtractionRules` / `fullBackupContent` excluding all app data.
- **Release logging** — Ktor header logging only in debug builds; R8 strips `Log.v` / `Log.d` in release.
- **Crash-log scrubbing** also redacts `token=` / `"token":` / `api_key` fields and IPv4 literals.
- **Onboarding token** is held in `OnboardingViewModel`, not the saved-instance Bundle; copies are flagged `EXTRA_IS_SENSITIVE`.
- **Cleartext guard** treats IPv6 ULA (`fc00::/7`) and unspecified addresses as private.
- **Host validation** rejects `@`, `?`, `#`, `\` in the manual-entry host field.
- **Gradle wrapper** pins `distributionSha256Sum`.

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
