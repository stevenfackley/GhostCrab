# Changelog

All notable changes to GhostCrab are documented here.  
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### iOS

The iOS / Mac Catalyst app (`ios/`) has not shipped a build yet. These changes come from `feat/ios-finish` (PR #127).

#### Added

- **Pairing links**: `ghostcrab://pair?p=…` QR codes and deep links (the `ghostcrab` URL scheme is registered, so the OS camera and Safari open them) prefill Manual Entry with the gateway URL, token and suggested profile name. Nothing connects until you tap Connect. Manual Entry also has a Pairing link field that takes a pasted link or gateway URL; on Mac Catalyst, where QR scanning is unavailable, this field is how you pair.
- **Model activation asks first**: tapping a model opens a confirmation, and repeat taps send one request.
- **Bundled fonts**: Inter (Regular, Medium, SemiBold, Bold) and JetBrains Mono (Regular, Medium) ship in the app with their OFL licenses. A test fails if any face falls back to the system font.
- **Clear all saved gateways** in Settings, behind a destructive confirmation. It removes saved profiles, corrupt-list backups and Keychain tokens.
- **ATS warning** under the Manual Entry host field when iOS will block plain HTTP to that host.
- **IPv6 literals** in Manual Entry, bare or bracketed. A zone id is dropped from the URL.
- **CI lane**: `.github/workflows/ios-pr.yml` runs on every PR that touches `ios/`, on the self-hosted Mac Mini. It runs the unit suite (Swift Testing) and an XCUITest launch smoke test on the iOS simulator, 214 tests in all, and compiles the Mac Catalyst build.

#### Changed

- Version 0.2.0 (`MARKETING_VERSION`), matching Android. CI still sets the build number.
- Each request sets its own idle timeout: 15 s, or 120 s for skill install and AI recommendations, with a 180 s cap per transfer. The old 15 s connect timeout was never applied, and a 30 s overall cap cut off long calls.
- Navigation: finishing onboarding and Config Editor's "Pick a gateway" land on one gateway picker, and a connect shows one dashboard directly on the picker instead of stacking screens. Each screen builds its ViewModel once.
- TestFlight release: `ExportOptions.plist` only exports (`destination=export`) and `altool` is the one upload path. `compileBitcode` is gone and CI owns the build number.

#### Fixed

- The app builds on Xcode 27 under Swift 6 strict concurrency. CI had never compiled it before, and the build-info script phase failed with a dependency cycle.
- Double taps no longer start parallel connects, deletes, onboarding steps, model activations, config saves or AI applies. Leaving a screen cancels its pending work.
- An unreadable saved-profile list is backed up and reported instead of being overwritten by the next save.
- Undecodable `/status`, `/models`, `/config` and `/api/skills` JSON reports the URL, HTTP status and decoding error instead of looking like a gateway with no models or config. HTML bodies (the upstream `/status` quirk) still connect.
- A failed Keychain token cleanup is logged instead of swallowed.
- QR scanner: Scan again, and coming back from Manual Entry, restart the camera; the first scan asks for camera access instead of saying "unavailable"; a scanner that cannot start shows why. A camera blocked by Screen Time or device management says so instead of pointing to the camera switch in Settings.
- Back while connecting no longer leaves "Error connecting to …" on the gateway picker: a cancelled connect ends disconnected, and a session the gateway had already accepted is disconnected.
- A pairing link re-delivered just as Manual Entry opens keeps its token instead of losing it to the first prefill.
- Leaving the config editor mid-load no longer shows an error.
- A skill install blocked by the cleartext policy shows the policy message instead of "Protocol error 0: …".
- A pairing link's name is saved as the profile name.
- Mac Catalyst builds no longer reference the iOS-only VisionKit scanner bridge.
- Mac Catalyst keeps its App Sandbox and network client entitlements: `xcodegen generate` rewrote the entitlements file from `project.yml` and dropped them, and the Mac App Store rejects an unsandboxed app. The unused `network.server` entitlement is gone.
- An IPv6 zone id (`%en0`) in a pairing link or pasted URL is dropped from the gateway URL, like Android.
- The release workflow's API key wipe called `shred`, which macOS does not ship. It now deletes the key and fails if the file is still there.

#### Security

- **Pairing token never in navigation state**: the token goes to an in-memory, one-shot `PairingHandoff` (scoped to the gateway's origin, expires after two minutes, redacted from descriptions), never into `Route` or the `NavigationPath`. A prefill for another host drops it, and so does editing the scheme, host or port by hand (unless the user typed the token), so one gateway's token is never sent to another. A hand edit also drops the link's suggested profile name.
- **Pairing links validated**: payloads over 4096 characters are rejected before parsing; only schema v1, `http`/`https` with a host, no userinfo and a port in 1–65535 are accepted; error text never echoes the payload.
- **Cleartext block typed**: a request the public-IP cleartext guard refuses reports `GatewayError.cleartextBlocked`, naming Settings > Security > Allow HTTP to public IPs, instead of "unreachable". The opt-in now travels on each request, so one client's setting no longer applies to every client.
- **ATS local-only**: `Info.plist` declares `NSAllowsLocalNetworking` and never `NSAllowsArbitraryLoads` ([ADR-006](docs/adr/0006-ios-ats-local-networking.md)). Plain HTTP works only to IP addresses, unqualified host names and `.local` names.
- **Privacy manifest**: `PrivacyInfo.xcprivacy` declares no tracking, no collected data and one required-reason API, UserDefaults (CA92.1). A test checks it ships in the bundle.
- **Host validation** rejects `@`, `?`, `#` and `\` in the Manual Entry host field.
- **Connection state redacted**: `print`, `dump` and string interpolation of the connection state show the session token as `<redacted>`.
- **Self-hosted CI**: the PR lane skips fork PRs and checks out without persisting the token. The release workflow validates the build number input and reads it, and the App Store Connect key id and issuer id, as shell variables instead of inline expressions; its checkout no longer persists the token either.

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
