# Changelog

All notable changes to GhostCrab are documented here.  
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

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

### Security

- Bearer tokens are never logged. `Authorization` headers stripped from Ktor logs.
- Crash handler sanitizes stack traces before logging.
- Release build: R8 full-mode minification + resource shrinking.
