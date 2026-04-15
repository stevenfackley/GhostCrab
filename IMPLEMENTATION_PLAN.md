# GhostCrab — Phased Implementation Plan

> **Project:** GhostCrab — Android-native remote client for OpenClaw Gateway
> **Stack:** Kotlin · Jetpack Compose · Ktor · Koin · NsdManager · DataStore (+ EncryptedSharedPreferences)
> **Plan Owner:** Steve (`stevenfackley@gmail.com`)
> **Plan Author:** Opus 4.6 (architect/reviewer pass)
> **Implementer:** Sonnet (phase-by-phase, **context cleared between phases**)
> **Source of Truth:** This file. Update the Progress Ledger at the end of every phase.

---

## 0. How To Use This Document (Read First, Every Session)

This plan is designed for **stateless execution**. Each phase below is a self-contained brief: an implementer arriving cold should be able to read only **§0 + §1 + the target phase** and have everything they need.

**Standard operating procedure for each phase:**

1. **Context Reload** — read §1 (Architecture Snapshot) and the target phase's `Context Reload` block.
2. **Verify Pre-Conditions** — confirm the prior phase's `Exit Criteria` are satisfied (run the verification commands listed there).
3. **Execute Tasks** — work the phase's `Task Breakdown` top-to-bottom. Use TodoWrite to track sub-tasks.
4. **Verify Acceptance Criteria** — every checkbox in the phase's `Acceptance Criteria` must pass.
5. **Update Progress Ledger** — flip the phase's status, fill in `Completed`, `Commit SHA`, and `Notes for Next Phase`.
6. **Hand Off** — write a one-paragraph handoff note in the phase's `Handoff` block. Stop. Do not start the next phase in the same session.

**Strict rules for implementers:**

- **Never silently default to the easy path.** If you deviate from this plan, document the trade-off in the phase's `Deviations` block with rationale.
- **Type safety is non-negotiable.** No `Any`, no unchecked casts, no swallowed exceptions. Use `sealed` hierarchies for state and errors per §1.4.
- **Environment variables and secrets** never go in source. Use `local.properties` / `BuildConfig` for dev defaults; document required env in `README.md`.
- **All public APIs get KDoc** with `@param`, `@return`, `@throws`, and edge-case notes.
- **Brand fidelity** — Phase 1 ships the design tokens; every subsequent phase consumes them. Do not introduce ad-hoc colors, fonts, or spacing values.
- **No work in `GhostCrab (1)` folder.** Canonical workspace is `GhostCrab/`.

---

## Progress Ledger

| # | Phase | Status | Started | Completed | Commit SHA | Implementer | Notes |
|---|-------|--------|---------|-----------|------------|-------------|-------|
| 0 | Repo Scaffold & Tooling | 🟢 Done | — | — | b5d939a | Sonnet 4.6 | — |
| 1 | Domain Contracts, Theme, DI | 🟢 Done | — | — | b5d939a | Sonnet 4.6 | — |
| 2 | Connection: Manual Entry + Auth Probe | 🟢 Done | — | — | 61e1ba9 | Sonnet 4.6 | — |
| 3 | Discovery: NsdManager + Scan UI | 🟢 Done | 2026-04-15 | 2026-04-15 | — | Sonnet 4.6 | Added material-icons-extended; fixed android:Theme.Material.NoTitleBar→NoActionBar; fixed missing dp import; fixed disconnect test |
| 4 | Dashboard, Health Polling, Security Banners | 🟢 Done | 2026-04-15 | 2026-04-15 | fd40cae | Sonnet 4.6 | ModelRepositoryStub; DomainModule fix (DiscoveryService clash removed); binary results dir workaround for Windows IDE lock; NSC cleartext deferred to Phase 9 (CIDR not supported in NSC) |
| 5 | **Onboarding Walkthrough (Noob Mode)** | ⬜ Not Started | — | — | — | — | — |
| 6 | Config Editor (Forms over `openclaw.json`) | ⬜ Not Started | — | — | — | — | — |
| 7 | Model Manager | ⬜ Not Started | — | — | — | — | — |
| 8 | AI Recommendations (Gateway-Proxied CLI) | ⬜ Not Started | — | — | — | — | — |
| 9 | Settings, Profile Management, About | ⬜ Not Started | — | — | — | — | — |
| 10 | Hardening: Tests, Crash Handling, Telemetry, Release | ⬜ Not Started | — | — | — | — | — |

**Status legend:** ⬜ Not Started · 🟡 In Progress · 🟢 Done · 🔴 Blocked · ⚪ Skipped (with rationale)

---

## 1. Architecture Snapshot (Stable Reference — Read Every Session)

### 1.1 What GhostCrab Is

A thin, stateless Android client that connects to **any reachable OpenClaw Gateway** (LAN or remote) and exposes the same configuration + model management capabilities as the WPF desktop app. AI recommendations are proxied through the Gateway. **No offline cache. No background sync. No agent runtime on-device.**

### 1.2 Layered Architecture

```
UI (Compose)  →  ViewModel (StateFlow)  →  Domain (interfaces)  →  Data (Ktor / NSD / DataStore)
```

- **Single-activity** Compose app with type-safe Compose Navigation.
- **DI:** Koin modules per layer (`appModule`, `dataModule`, `domainModule`, `uiModule`).
- **Concurrency:** `kotlinx.coroutines`. All I/O is `suspend`. UI consumes hot `StateFlow`.

### 1.3 Module Map

```
app/
└── src/main/kotlin/com/openclaw/ghostcrab/
    ├── GhostCrabApp.kt
    ├── di/                AppModule.kt, DataModule.kt, DomainModule.kt, UiModule.kt
    ├── domain/
    │   ├── model/         GatewayConnection, DiscoveredGateway, OpenClawConfig, ModelInfo,
    │   │                  AIRecommendation, AuthRequirement, RecommendationContext
    │   ├── exception/     GatewayException sealed hierarchy
    │   └── repository/    GatewayConnectionManager, ConfigRepository, ModelRepository,
    │                      DiscoveryService, AIRecommendationService, ConnectionProfileRepository
    ├── data/
    │   ├── api/           OpenClawApiClient, dto/* (serializable wire types)
    │   ├── discovery/     NsdDiscoveryServiceImpl
    │   ├── storage/       ConnectionProfileStore (DataStore + EncryptedSharedPreferences)
    │   └── impl/          GatewayConnectionManagerImpl, ConfigRepositoryImpl, …
    └── ui/
        ├── theme/         Color.kt, Type.kt, Shape.kt, GhostCrabTheme.kt, BrandTokens.kt
        ├── navigation/    NavGraph.kt, Routes.kt
        ├── components/    SecurityBanner, ConnectionStatusBar, ErrorSnackbar, GlassSurface, …
        ├── connection/    ConnectionPickerScreen + ViewModel, ScanScreen + ViewModel,
        │                  ManualEntryScreen + ViewModel
        ├── onboarding/    OnboardingScreen + ViewModel, steps/*  (Phase 5)
        ├── dashboard/     DashboardScreen + ViewModel
        ├── config/        ConfigEditorScreen + ViewModel, sections/*
        ├── models/        ModelManagerScreen + ViewModel
        ├── ai/            AIRecommendationScreen + ViewModel
        └── settings/      SettingsScreen + ViewModel
```

### 1.4 Canonical Contracts (Implementers must not change signatures without an ADR)

See the project instructions document for the full Kotlin signatures of:

- `GatewayConnectionManager` + sealed `GatewayConnection` + sealed `AuthRequirement`
- `DiscoveryService` + `DiscoveredGateway`
- `ConfigRepository`
- `AIRecommendationService`
- `OpenClawApiClient` (Ktor)
- Sealed `GatewayException` hierarchy with `isRetryable: Boolean`

These are the **frozen contracts** for the v1.0 codebase. Phases 1–10 implement them; they do not redefine them.

### 1.5 Brand Tokens (from `Branding.md`)

| Token | Value | Use |
|-------|-------|-----|
| `colorAbyss` | `#0F1115` | Default background (dark) |
| `colorAbyssRaised` | `#16191F` | Raised surface base |
| `colorGlass` | `rgba(255,255,255,0.06)` | Glassmorphism overlay |
| `colorCyanPrimary` | `#5BE9FF` (luminous cyan) | Primary actions, connected state |
| `colorCyanPulse` | `#7BD8FF` | Scanning / mDNS pulse |
| `colorAmberWarn` | `#E0A458` | Disconnected / unreachable |
| `colorCrimsonError` | `#FF4D6D` | Auth / validation errors |
| Font (UI) | Inter (fallback Roboto) | All interface elements |
| Font (Mono) | JetBrains Mono (fallback Roboto Mono) | IPs, ports, JSON, CLI output |
| Logo asset | `GhostCrab/logo_cropped.png` | App icon source, splash, app bar |

**Rules:**
- HTTP-mode banner: amber background, crimson outline if also `auth: none`.
- Animations restricted to state transitions; pulse only for active scan; no decorative motion.
- Surfaces composited as 5–10% white-alpha over `colorAbyss`. Use `GlassSurface` composable (Phase 1).

### 1.6 Out-of-Scope (v1.0)

WebSocket streaming · offline config cache · iOS · agent runtime on-device · provider-API keys on-device · multi-active-connection · Play Billing.

---

## 2. Phase Briefs

> Each phase below is **independently executable**. The Context Reload block is the implementer's quick-rehydrate pack.

---

### Phase 0 — Repo Scaffold & Tooling

**Goal:** Get a buildable, lintable, testable empty Compose app on disk inside `GhostCrab/`. No business logic.

**Context Reload:**
- Working directory: `/sessions/upbeat-elegant-wright/mnt/GhostCrab/`
- `Branding.md` and `logo_cropped.png` already exist there. Do not move them.
- This phase produces only project skeleton + dependency declarations.

**Task Breakdown:**

1. Create Gradle multi-module-ready single-module project. Root files: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`.
2. Configure `app/build.gradle.kts`:
   - `compileSdk = 35`, `minSdk = 26`, `targetSdk = 35`
   - Kotlin `2.0+`, Compose Compiler plugin, `kotlinx.serialization` plugin
   - JVM target 17
   - `buildTypes`: `debug` (BuildConfig.DEBUG = true, applicationIdSuffix `.debug`), `release` (R8 minify, proguard)
   - `buildFeatures { compose = true; buildConfig = true }`
3. Add `libs.versions.toml` entries for: AndroidX core/lifecycle/activity-compose, Compose BOM, Material3, Navigation-Compose, Ktor (client-core, client-okhttp, client-content-negotiation, client-auth, client-logging), kotlinx-serialization-json, Koin (core, android, compose), DataStore (preferences), AndroidX Security (EncryptedSharedPreferences), Coil-Compose, Coroutines, JUnit5, Turbine, MockK, Espresso (instrumented).
4. Create directory tree per §1.3 with `.gitkeep` placeholders.
5. `AndroidManifest.xml`:
   - Permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_WIFI_MULTICAST_STATE` (mDNS), `ACCESS_WIFI_STATE`.
   - `usesCleartextTraffic = true` (with `network_security_config.xml` warning policy — see Phase 4).
   - Single `MainActivity` with Compose theme stub.
6. Stub `GhostCrabApp : Application` registering an empty Koin start.
7. Stub `MainActivity` rendering `Text("GhostCrab")`.
8. Add `.editorconfig`, `.gitignore` (Android-standard), and a top-level `README.md` skeleton (see §4).
9. Wire ktlint + detekt as Gradle tasks. CI-readiness only — do not run on every build yet.
10. Verify `./gradlew assembleDebug` succeeds.

**Acceptance Criteria:**
- [ ] `./gradlew assembleDebug` exits 0.
- [ ] `./gradlew lint detekt` runs (failures allowed at this stage but task wiring works).
- [ ] APK installs on Android 8.0+ emulator and shows the placeholder screen.
- [ ] Directory tree matches §1.3 exactly.
- [ ] No business logic, no models, no API calls present yet.

**Deliverables:** Buildable repo skeleton.

**Risks / Trade-offs:**
- Compose BOM vs pinned versions → use BOM (one upgrade lever, predictable transitives).
- KSP vs KAPT → KSP only (Koin needs neither; no annotation processors used).

**Handoff Block:** _to be filled by implementer at end of phase_

---

### Phase 1 — Domain Contracts, Theme, DI Wiring

**Goal:** Land the contracts (interfaces, sealed hierarchies, exceptions), the brand theme, and Koin module skeletons. **No implementations yet** beyond the theme.

**Context Reload:**
- Phase 0 must be complete; `./gradlew assembleDebug` works.
- Read §1.4 (Canonical Contracts) and §1.5 (Brand Tokens). These are the inputs.
- This phase is "compile-only" — every interface has a doc comment and an empty Koin binding (TODO body).

**Task Breakdown:**

1. **Domain models** (`domain/model/`): create immutable `data class` / `sealed interface` for every type listed in the project instructions §3.2 — `GatewayConnection`, `AuthRequirement`, `DiscoveredGateway`, `OpenClawConfig` (start with placeholder fields + raw `JsonElement` escape hatch), `ModelInfo`, `AIRecommendation`, `RecommendationContext`. All `@Serializable` where they cross the wire.
2. **Exceptions** (`domain/exception/`): full sealed `GatewayException` hierarchy with `isRetryable` per project instructions §3.4.
3. **Repository interfaces** (`domain/repository/`): `GatewayConnectionManager`, `DiscoveryService`, `ConfigRepository`, `ModelRepository` (extract `getModels` from `ConfigRepository` for SRP), `AIRecommendationService`, `ConnectionProfileRepository` (new — local profile CRUD).
4. **Theme** (`ui/theme/`):
   - `BrandTokens.kt` — top-level `object` exposing every token in §1.5.
   - `Color.kt` — Material3 `ColorScheme` built from tokens (dark scheme is default; light scheme is a near-mirror but explicitly secondary).
   - `Type.kt` — Inter UI font + JetBrains Mono via `androidx.compose.ui.text.googlefonts`.
   - `Shape.kt`, `Spacing.kt` (4dp/8dp/16dp/24dp scale).
   - `GhostCrabTheme.kt` — the composable wrapper.
   - `GlassSurface.kt` (`ui/components/`) — composable that renders the 6% white overlay.
5. **Koin modules** (`di/`): declare every binding as `TODO("Phase X")`. Wire them into `GhostCrabApp`.
6. **Navigation skeleton** (`ui/navigation/`): `Routes` sealed class with all destinations from project instructions §4 + onboarding (Phase 5). `NavGraph` composable that just routes to a placeholder per screen.
7. **Logo / icon**: convert `logo_cropped.png` to adaptive icon (foreground + background). Splash screen uses Material3 `androidx.core.splashscreen` with the cyan logo on `colorAbyss`.

**Acceptance Criteria:**
- [ ] `./gradlew assembleDebug` succeeds.
- [ ] `./gradlew testDebugUnitTest` runs (no tests yet, but task green).
- [ ] App launches to splash → empty navigation host. No crashes.
- [ ] Every interface in `domain/repository/` has a KDoc with `@param`/`@return`/`@throws`.
- [ ] No hardcoded colors or font references outside `ui/theme/`.
- [ ] Koin starts without errors (every binding is a `single { TODO(...) }` — never resolved at app start).

**Deliverables:** Frozen contract surface + theme + nav skeleton.

**Risks / Trade-offs:**
- Putting exceptions in `domain` couples errors to domain — accepted; alternatives (Result wrappers) hurt ergonomics in coroutines.
- Single `OpenClawConfig` data class vs strongly-typed nested config → start with `Map<String, JsonElement>` view + typed wrappers added incrementally in Phase 6 (avoids brittle schema lock-in).

**Handoff Block:** _to be filled_

---

### Phase 2 — Connection: Manual Entry + Auth Probe + Profile Storage

**Goal:** User can type a Gateway URL, optionally a token, hit Connect, and see Connected/Failed state. Profiles persist encrypted between launches.

**Context Reload:**
- Contracts from Phase 1 are frozen.
- Implement `OpenClawApiClient` (Ktor), `GatewayConnectionManagerImpl`, `ConnectionProfileStore` (DataStore for metadata + EncryptedSharedPreferences for tokens).
- `/health` is unauthenticated. `/status` may require auth. Use this asymmetry for `probeAuth`.

**Task Breakdown:**

1. `OpenClawApiClient` per project instructions §3.3 — including the `handleApiErrors` mapper. Strip `Authorization` headers from logging. Timeouts: connect 15s, request 30s.
2. `GatewayConnectionManagerImpl`:
   - `probeAuth(url)`: GET `/health` → if 2xx, GET `/status` (no token) → 401/403 ⇒ `AuthRequirement.Token`; 200 ⇒ `AuthRequirement.None`. (Password mode deferred — surface as `Token` for now and document.)
   - `connect(url, token?)`: probe → instantiate authenticated client → fetch `/status` → parse version + capabilities → emit `Connected`. On any failure, emit `Error` and rethrow.
   - `disconnect()`: cancel any held client, emit `Disconnected`.
3. `ConnectionProfileStore`:
   - DataStore (Preferences) for: profile id, display name, URL, last-connected timestamp, has-token flag.
   - EncryptedSharedPreferences (MasterKey AES256_GCM_SPEC) for: bearer token bytes keyed by profile id.
   - On Keystore decryption failure (factory reset case) → emit a `ProfileNeedsReauthEvent` and clear the encrypted entry.
4. ViewModels:
   - `ConnectionPickerViewModel` — exposes `StateFlow<List<ProfileSummary>>`, actions `select(id)`, `delete(id)`, `addNew()`.
   - `ManualEntryViewModel` — fields `url`, `token`, action `connect()`. Validates URL with `android.util.Patterns.WEB_URL` + custom port check. Token field is masked with reveal toggle.
5. UI:
   - `ConnectionPickerScreen` — list of saved profiles (glass cards) + FAB to add manually + secondary action "Scan LAN" (routes to Phase 3 placeholder).
   - `ManualEntryScreen` — URL field (mono font), token field (mono, masked), Connect button, inline error display using `GatewayException.message` verbatim (per Branding §6 voice rules).
   - `ConnectionStatusBar` component — top app bar slot showing state + protocol badge (HTTP/HTTPS) + auth mode pill.
6. Wire Koin bindings (replace `TODO`s for this phase's classes only).

**Acceptance Criteria:**
- [ ] Can connect to a real OpenClaw Gateway on localhost.
- [ ] HTTP gateways show the amber HTTP banner.
- [ ] Auth-required gateway without token shows `GatewayAuthException` message.
- [ ] Unreachable URL surfaces `GatewayUnreachableException` with the URL embedded.
- [ ] Profiles persist across app kill; tokens survive but are unreadable on a different device (Keystore boundary).
- [ ] Unit tests: `GatewayConnectionManagerImpl` happy + 4 error paths (mock Ktor engine via `MockEngine`).
- [ ] No token ever appears in logcat (verify with `adb logcat | grep -i bearer` — should be empty).

**Deliverables:** Working manual connect flow + persistent profiles.

**Risks / Trade-offs:**
- `MockEngine` over WireMock — faster, no extra port, sufficient for our coverage.
- Probing `/status` to infer auth mode is heuristic; documented that future Gateway versions could expose `/auth-mode`. ADR-002 placeholder.

**Handoff Block:** _to be filled_

---

### Phase 3 — Discovery: NsdManager + Scan UI

**Goal:** User taps "Scan LAN", sees gateways appear in real time, taps one to pre-fill manual-entry (or auto-connect if previously seen).

**Context Reload:**
- Service type: `_openclaw-gw._tcp.` Default port `18789`.
- Android NsdManager `resolveService` is single-shot and historically flaky — wrap with timeout + retry once.
- WIFI multicast lock is required on some devices; acquire `WifiManager.MulticastLock` for the duration of the scan.

**Task Breakdown:**

1. Implement `NsdDiscoveryServiceImpl` per project instructions §3.5 with these additions:
   - Acquire/release `MulticastLock` around `discoverServices`/`stopServiceDiscovery`.
   - Per-service `resolveService` wrapped in `withTimeoutOrNull(5_000)`.
   - Deduplicate by `instanceName`.
2. `ScanViewModel`:
   - `StateFlow<ScanState>` — `Idle | Scanning(progressMs) | Results(list, scanCompleted) | Error(reason)`.
   - Auto-start scan on screen entry; auto-stop after 10s.
   - Manual "Scan again" button.
3. `ScanScreen`:
   - Pulse animation on the radar/eye-stalk icon while scanning (the only animated brand element — see Branding §5).
   - Empty state copy (per voice rules): "No OpenClaw gateways detected on this network. mDNS may be blocked. Enter a URL manually."
   - Tapping a result → if it matches a saved profile by URL → connect immediately; else → pre-fill `ManualEntryScreen`.
4. **mDNS spoofing mitigation** (per project instructions §5.1): after selecting a discovered gateway, always run `probeAuth` + display the gateway's `displayName` and `version` for user verification before saving the profile.
5. Permissions: NSD requires no runtime permission on Android 8+; verify on test device and document.

**Acceptance Criteria:**
- [ ] Discovers a real OpenClaw instance on the same Wi-Fi within 5 s of opening Scan screen.
- [ ] Multiple gateways appear independently as resolved.
- [ ] On a network with mDNS blocked, scan completes empty without crashing.
- [ ] No memory leak: stopping the screen releases the multicast lock and listener (verify with LeakCanary in debug builds).
- [ ] Unit tests for state machine in `ScanViewModel` (use a fake `DiscoveryService`).

**Deliverables:** End-to-end LAN discovery → profile creation.

**Risks / Trade-offs:**
- Some Android OEM Wi-Fi stacks block multicast aggressively (Xiaomi, OnePlus). Documented limitation; manual entry remains the universal fallback.

**Handoff Block:** _to be filled_

---

### Phase 4 — Dashboard, Health Polling, Security Banners

**Goal:** Once connected, land on a dashboard showing gateway identity, health, model count, and quick actions. Surface security warnings prominently.

**Context Reload:**
- Connection state lives in `GatewayConnectionManager.connectionState`.
- Polling: `/health` every 30s while dashboard is foregrounded; cancel on backgrounding.
- Security banners: HTTP, no-auth, self-signed TLS — each has its own component with explicit, documented criteria.

**Task Breakdown:**

1. `DashboardViewModel`:
   - Combines `connectionState`, periodic `/health` polls (`flow { while(true) { emit(api.health()); delay(30_000) } }.flowOn(IO)`), and `modelRepository.getModels()` (one-shot, refreshable).
   - Exposes a single `DashboardUiState` (sealed) — `Loading | Ready(GatewayInfo, HealthSnapshot, models) | Degraded(reason) | Disconnected`.
2. `DashboardScreen`:
   - Header: gateway display name (Inter Bold), URL (mono), version pill, capability chips.
   - Health card: green dot + last-poll timestamp; amber dot if last poll > 60s ago; crimson if last poll failed.
   - Model summary: count + active model name (mono).
   - Quick actions row: Configure · Models · AI Recommend · Disconnect.
3. Security banners (`ui/components/SecurityBanner.kt`):
   - **HTTP banner** (amber): "Connection is unencrypted (HTTP). Anyone on this network can read configuration data."
   - **No-auth banner** (crimson outline, dismissible-once-per-profile): "This gateway accepts unauthenticated requests. ~17,000 OpenClaw instances are exposed publicly. [Learn more]" — link opens in-app browser to OpenClaw security docs.
   - **Self-signed TLS** (Phase 10 may revisit): for now, refuse self-signed unless user explicitly opts in via Settings flag.
4. `network_security_config.xml`: allow cleartext to private IP ranges only (`10.*`, `172.16-31.*`, `192.168.*`, `localhost`); deny cleartext to public IPs unless overridden in Settings.

**Acceptance Criteria:**
- [ ] Dashboard renders within 1 s of successful connect.
- [ ] Killing the gateway mid-session flips dashboard to `Degraded` within 60 s.
- [ ] HTTP and no-auth banners appear under correct conditions and never simultaneously hidden.
- [ ] Polling stops when the screen leaves composition (verify with `Logging` interceptor count).
- [ ] No bare strings — all copy in `strings.xml` (translatable).

**Deliverables:** Connected-state home screen.

**Risks / Trade-offs:**
- 30s poll vs adaptive backoff — chose fixed 30s for predictability; adaptive added in Phase 10 if metrics warrant.

**Handoff Block:** _to be filled_

---

### Phase 5 — Onboarding Walkthrough (Noob Mode) ⭐ NEW

**Goal:** A first-run, opt-in tutorial that walks a user with **zero OpenClaw experience** through (a) what OpenClaw is, (b) how to install/start a Gateway, (c) how to verify it's reachable, (d) connecting GhostCrab to it. Resumable and skippable.

**Context Reload:**
- Triggered automatically on first launch with no saved profiles. Re-accessible from Settings → "Open Setup Walkthrough".
- Walkthrough state persists in DataStore (`onboarding_step`, `onboarding_completed`).
- Walkthrough does NOT install software — it provides commands the user copies and runs themselves. We will not execute privileged actions on the user's machines.

**Step Design (each step is a Compose screen with a brand-styled scaffold):**

1. **Welcome** — Logo, one-paragraph "GhostCrab is a remote control for OpenClaw Gateway. Let's get you set up."
   - CTA: `Get Started` · `Skip — I already have a Gateway`.
2. **What is OpenClaw?** — 4 bullet primer (gateway, models, auth, mDNS). Include visual diagram (asset shipped in `res/drawable/onboarding_arch.svg`).
3. **Install the Gateway (choose your platform):**
   - Tabs: Linux/macOS · Windows · Raspberry Pi · Docker.
   - Each tab shows the install command in a copyable mono code block (e.g., `curl -fsSL https://openclaw.ai/install.sh | sh`).
   - "Copy" button → puts in clipboard → toast "Copied. Paste this into a terminal on the machine that will host the Gateway."
   - Secondary link: "Open install docs in browser" (uses Custom Tabs).
4. **Start the Gateway:**
   - Show command: `openclaw gateway start --port 18789`.
   - Explain default port + auth modes (token/none) with the security warning replicated from §1.5.
   - Copyable suggested `openclaw.json` snippet for token auth (with a generated random token — generated client-side, never sent anywhere).
5. **Verify it's running:**
   - "On the host machine, open a browser to http://localhost:18789/health — you should see `{\"status\":\"ok\"}`."
   - Asks the user to confirm before continuing.
6. **Find it on your phone:**
   - "Now make sure your phone is on the same Wi-Fi as the host machine."
   - Two paths: **Auto-discover** (jumps to Scan screen with onboarding context — Scan returns to onboarding on success) or **Enter URL manually** (jumps to Manual Entry).
7. **Connect:**
   - When the user successfully connects, onboarding marks itself complete and routes to Dashboard with a one-time "You're connected. Try editing config or asking the AI for recommendations." nudge.
8. **Troubleshooting drawer** — accessible from any step:
   - "Health check fails" → checklist (gateway running? port open? firewall? bound to 0.0.0.0?).
   - "No gateways found in scan" → checklist (same Wi-Fi? mDNS allowed? client isolation off?).
   - "Auth error" → token in `openclaw.json` matches token entered in app?

**Architecture:**

- `OnboardingViewModel` exposes `StateFlow<OnboardingStep>` (sealed class with one entry per step) + `next()`, `back()`, `skip()`, `goTo(step)`.
- `OnboardingRepository` (DataStore-backed) persists progress.
- Each step is a separate `@Composable` under `ui/onboarding/steps/`.
- A reusable `OnboardingScaffold` provides progress dots, back/next bar, and the "Skip" affordance.
- All copy lives in `strings.xml` keyed `onboarding_*`.
- A `CodeBlock` composable (mono font, copy button, syntax-flavored color hint) is added to `ui/components/` and reused throughout the walkthrough.
- Token generator: `SecureRandom` → 32 bytes → URL-safe Base64. Surfaced read-only with a copy button.

**Cross-Phase Hooks:**
- Scan screen (Phase 3) accepts an `?onboarding=true` nav arg → on first successful selection, return to Onboarding step 7 instead of Dashboard.
- Manual Entry (Phase 2) same hook.
- Settings (Phase 9) exposes a "Replay Walkthrough" action.

**Acceptance Criteria:**
- [ ] First launch with zero profiles routes into Welcome step.
- [ ] User can `Skip` at any step and land on Connection Picker.
- [ ] Progress survives app kill mid-walkthrough.
- [ ] Generated token is cryptographically random (assert `SecureRandom` source in unit test).
- [ ] All install/start commands are copyable and the toast confirms.
- [ ] Troubleshooting drawer is reachable from every step.
- [ ] Walkthrough completes successfully against a real Gateway end-to-end on a fresh device.
- [ ] Accessibility: every step navigable by TalkBack; code blocks are content-described as "Code: `<command>`".

**Risks / Trade-offs:**
- Hardcoding install URLs creates maintenance debt — accepted; alternative (fetching from a server) adds an availability dependency at first-run, the worst possible time.
- Showing a generated token in plaintext is intentional — the user must paste it into `openclaw.json`. Documented; warned in copy.

**Handoff Block:** _to be filled_

---

### Phase 6 — Config Editor (Forms over `openclaw.json`)

**Goal:** Read the gateway's config, render it as typed forms (with raw JSON fallback for unknown sections), write back via PATCH.

**Context Reload:**
- `ConfigRepository.getConfig()` returns the full `openclaw.json` parsed.
- `updateConfig(section, value)` uses JSON merge-patch.
- Optimistic UI is **forbidden** here — gateway is the source of truth. After every write, re-read and reconcile.

**Task Breakdown:**

1. `ConfigRepositoryImpl` over `OpenClawApiClient`.
2. Typed wrappers for the most common sections (start with `gateway.http`, `gateway.auth`, `gateway.mdns`, `models`, `agents`). Unknown sections fall through to a `JsonElement` editor.
3. `ConfigEditorScreen` with section list (left rail / top tabs depending on width) → form on right.
4. Form components: `ToggleRow`, `EnumDropdownRow`, `IntFieldRow` (with min/max), `StringFieldRow` (mono variant for paths/URLs), `JsonEditorRow` (raw fallback).
5. Validation: client-side guards (port range, URL format) + server-side reconciliation. `ConfigValidationException` surfaces field + reason inline.
6. Diff view: before saving, show a "Pending changes" sheet listing each modified key + old → new value. User must confirm.

**Acceptance Criteria:**
- [ ] Round-trip edit on `gateway.http.port`: change → save → re-read → value matches.
- [ ] Invalid value (e.g., port 99999) surfaces field-level error without losing other edits.
- [ ] Unknown section appears in raw JSON editor and round-trips losslessly.
- [ ] Concurrent edit by another client is detected by re-read diff and surfaced as "Server values changed since you opened this section. [Reload]".

**Deliverables:** Form-based config parity with the WPF app's core sections.

**Risks / Trade-offs:**
- Maintaining typed wrappers adds surface area; mitigated by keeping the raw fallback always present.

**Handoff Block:** _to be filled_

---

### Phase 7 — Model Manager

**Goal:** List models the gateway knows about, show provider/auth/status, swap the active one.

**Task Breakdown:**

1. `ModelRepositoryImpl` over `/api/models/status` and the model-swap endpoint (verify exact path during this phase — open question in project instructions §10).
2. `ModelManagerScreen`: list of models (mono provider/id, status pill, capability chips), tap → detail sheet → "Set active" button.
3. Confirmation dialog before swapping: "This will affect all sessions on this gateway."

**Acceptance Criteria:**
- [ ] Active model is visually distinct.
- [ ] Swap reflects on Dashboard within one health-poll cycle.
- [ ] Provider auth failures (gateway-side) surface as a per-model warning chip.

**Handoff Block:** _to be filled_

---

### Phase 8 — AI Recommendations (Gateway-Proxied CLI)

**Goal:** User describes what they want ("best coding model on 16GB RAM") → gateway invokes its CLI (Gemini CLI etc.) → app displays the recommendation with optional one-click apply for suggested config changes.

**Context Reload:**
- Gateway endpoint may not exist yet — open question §10. If absent, ship a graceful "AI recommendations require the `skill-ai-recommend` skill on the gateway." empty state with link to install docs.
- Pro-tier gating is a feature flag (`BuildConfig.AI_PRO_ENABLED`). For v1.0, default true.

**Task Breakdown:**

1. `AIRecommendationServiceImpl` with `isAvailable()` probing for the skill's presence (try a HEAD or capability query first).
2. `AIRecommendationScreen` — query field (Inter), context auto-collected (active config + reported hardware), "Ask" button. Streaming text rendering if the gateway supports SSE; else single-shot.
3. Apply-suggestions sheet: parse structured `suggested_changes` array → for each, show diff → user toggles which to apply → "Apply selected" routes through `ConfigRepository.updateConfig`.
4. Errors: `AIServiceUnavailableException` (skill missing) and `AIQuotaExceededException` (rate limit) get distinct empty/error states.

**Acceptance Criteria:**
- [ ] Returns a recommendation against a gateway with the AI skill installed.
- [ ] Gracefully degrades when skill is absent.
- [ ] Applied suggestions actually change config and re-read confirms.

**Handoff Block:** _to be filled_

---

### Phase 9 — Settings, Profile Management, About

**Goal:** Centralized place for app preferences, profile editing, walkthrough replay, license info.

**Task Breakdown:**

1. Settings sections:
   - **Connections:** edit/delete saved profiles; toggle "Allow cleartext to public IPs" (default off).
   - **Onboarding:** "Replay Walkthrough" action.
   - **Security:** rotate stored token; clear all profiles.
   - **About:** version, build SHA, OpenClaw API compatibility range, links to source/docs.
2. Theme settings deferred (dark only for v1.0 per Branding §3).

**Acceptance Criteria:**
- [ ] Every action has a confirmation where destructive.
- [ ] About screen exposes `BuildConfig.VERSION_NAME`, `BuildConfig.GIT_SHA`.

**Handoff Block:** _to be filled_

---

### Phase 10 — Hardening: Tests, Crash Handling, Telemetry, Release

**Goal:** Make it shippable.

**Task Breakdown:**

1. **Test coverage targets:** ViewModels ≥ 80% line coverage, repositories ≥ 70%, UI smoke tests (Compose UI Test) for: Connection Picker, Manual Entry, Scan, Dashboard, Onboarding happy path, Config Editor round-trip.
2. **Crash handling:** install a `Thread.UncaughtExceptionHandler` that strips tokens/URLs from stack traces before any logging. No third-party crash reporter at v1.0 (privacy default).
3. **Telemetry:** none in v1.0. Document the decision in `docs/PRIVACY.md`.
4. **Release wiring:** signing config from env vars, R8 rules verified, `assembleRelease` produces a signed APK and AAB.
5. **README + docs:** complete the README skeleton from Phase 0 with quick-start, architecture diagram (Mermaid), supported gateway versions, FAQ.
6. **ADR backfill:** write ADR-001 (Kotlin/Compose), ADR-002 (auth-mode probing heuristic), ADR-003 (no offline cache), ADR-004 (gateway-proxied AI).
7. **Release checklist** (see §3).

**Acceptance Criteria:**
- [ ] All coverage targets met.
- [ ] `assembleRelease` produces a signed AAB under `app/build/outputs/bundle/release/`.
- [ ] LeakCanary clean for a 10-minute exploratory session.
- [ ] Manual QA pass against §3 checklist.

**Handoff Block:** _to be filled_

---

## 3. Release Checklist (used in Phase 10 and every release)

- [ ] Version bumped in `app/build.gradle.kts` (`versionCode`, `versionName`).
- [ ] CHANGELOG.md updated.
- [ ] `assembleRelease` clean.
- [ ] Manual: connect to a token-auth gateway over HTTP — banner appears.
- [ ] Manual: connect to a no-auth gateway — crimson banner appears.
- [ ] Manual: scan empty network — graceful empty state.
- [ ] Manual: kill gateway mid-session — Dashboard degrades within 60 s.
- [ ] Manual: walkthrough end-to-end on a fresh install.
- [ ] No tokens, no URLs in `adb logcat`.
- [ ] APK size < 15 MB.
- [ ] App launches in < 1.5 s on a Pixel 4a-class device.

---

## 4. README Skeleton (created in Phase 0, completed in Phase 10)

```
# GhostCrab
> Remote client for OpenClaw Gateway — Android, native Kotlin/Compose.

## Quick Start
## Architecture
## Supported Gateway Versions
## Building
## Privacy
## License
```

---

## 5. Open Questions (track and resolve as phases progress)

1. Exact endpoint for model swap. — Resolve in Phase 7.
2. Does an `skill-ai-recommend` exist or must we build it? — Resolve in Phase 8.
3. Gateway version negotiation strategy. — Resolve in Phase 4 (probably a `min_supported` constant + warning banner).
4. Distribution channel (sideload vs Play Store) — defer to post-v1.0.

---

## 6. Deviations Log

> Implementers append here whenever they diverge from this plan, with rationale.

| Date | Phase | Deviation | Rationale | Approved by |
|------|-------|-----------|-----------|-------------|
| — | — | — | — | — |

---

## 7. Architecture Decision Records

ADRs live under `docs/adr/NNNN-title.md`. The plan reserves these IDs:

- ADR-001 — Kotlin + Compose over React Native
- ADR-002 — Auth-mode probing via `/health` + `/status` asymmetry
- ADR-003 — Stateless client; no offline config cache
- ADR-004 — Gateway-proxied AI vs direct LLM API
- ADR-005 — Onboarding does not execute privileged actions on user machines

Backfilled in Phase 10; created earlier if a deviation forces the decision sooner.
