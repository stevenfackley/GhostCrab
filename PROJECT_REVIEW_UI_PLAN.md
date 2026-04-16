# GhostCrab Project Review and UI Rework Plan

Date: April 15, 2026

## Review Findings

### 1. Active DI graph still contains placeholder and duplicate bindings

Severity: High

Files:
- [app/src/main/kotlin/com/openclaw/ghostcrab/di/AppModule.kt](/C:/Users/steve/projects/GhostCrab/app/src/main/kotlin/com/openclaw/ghostcrab/di/AppModule.kt:10)
- [app/src/main/kotlin/com/openclaw/ghostcrab/di/DomainModule.kt](/C:/Users/steve/projects/GhostCrab/app/src/main/kotlin/com/openclaw/ghostcrab/di/DomainModule.kt:9)
- [app/src/main/kotlin/com/openclaw/ghostcrab/di/DataModule.kt](/C:/Users/steve/projects/GhostCrab/app/src/main/kotlin/com/openclaw/ghostcrab/di/DataModule.kt:19)

`appModule` includes both `domainModule` and `dataModule`, but `domainModule` still registers:
- `TODO("Phase 6")` for `ConfigRepository`
- `ModelRepositoryStub()` for `ModelRepository`
- `TODO("Phase 8")` for `AIRecommendationService`

Those override the concrete bindings in `dataModule`. Koin last-one-wins: `domainModule` is included after `dataModule` in `appModule`, so `ConfigRepository` resolves to `TODO("Phase 6")` — a guaranteed `NotImplementedError` crash at first injection. `ModelRepository` resolves silently to the stub, so all model screens show fake data.

Recommended update:
- Remove completed-phase repository bindings from `domainModule`.
- Keep `domainModule` for pure domain-only wiring, or delete it entirely until Phase 8 needs it.
- Add a boot test that starts Koin and resolves all active ViewModels.

### 2. HTTP error handling breaks cancellation and hides non-network failures

Severity: High

File:
- [app/src/main/kotlin/com/openclaw/ghostcrab/data/api/OpenClawApiClient.kt](/C:/Users/steve/projects/GhostCrab/app/src/main/kotlin/com/openclaw/ghostcrab/data/api/OpenClawApiClient.kt:244)

`safeRequest` already has specific catches for `HttpRequestTimeoutException`, `SSLException`, `UnknownHostException`, and `ConnectException`, but the final `catch (e: Exception)` catch-all has two gaps:
- `CancellationException` is not re-thrown — coroutine cancellation is silently swallowed
- `SerializationException` / `JsonConversionException` are converted to `GatewayUnreachableException`, misreporting schema/parsing bugs as connectivity failures

Recommended update:
- Re-throw `CancellationException` (or `kotlinx.coroutines.CancellationException`) before the catch-all
- Add a distinct catch for `SerializationException` mapped to a new `GatewayApiException` or similar
- Leave the final catch-all for truly unexpected exceptions

### 3. First-launch onboarding navigation can be lost

Severity: Medium

Files:
- [app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/ConnectionPickerViewModel.kt](/C:/Users/steve/projects/GhostCrab/app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/ConnectionPickerViewModel.kt:38)
- [app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/ConnectionPickerScreen.kt](/C:/Users/steve/projects/GhostCrab/app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/ConnectionPickerScreen.kt:68)

The first-launch navigation event uses `MutableSharedFlow(replay = 0)`. Because the event may emit before the composable starts collecting, onboarding navigation can be dropped. The use of `stateIn(..., emptyList())` makes this more likely because `profiles.first()` can complete immediately from the initial empty state.

Recommended update:
- Replace this with stable UI state such as `shouldShowOnboarding`
- or use a replaying one-shot event with explicit consume semantics
- add a test for cold start with zero profiles and incomplete onboarding

### 4. Dashboard health polling leaks clients

Severity: Medium

Files:
- [app/src/main/kotlin/com/openclaw/ghostcrab/di/UiModule.kt](/C:/Users/steve/projects/GhostCrab/app/src/main/kotlin/com/openclaw/ghostcrab/di/UiModule.kt:18)
- [app/src/main/kotlin/com/openclaw/ghostcrab/ui/dashboard/DashboardViewModel.kt](/C:/Users/steve/projects/GhostCrab/app/src/main/kotlin/com/openclaw/ghostcrab/ui/dashboard/DashboardViewModel.kt:66)

The injected `healthChecker` creates a new unauthenticated `OpenClawApiClient` on every poll and never closes it.

Recommended update:
- inject a reusable health-check service
- or create/close the probe client inside the checker
- add a test or instrumentation check around repeated polling lifecycle

### 5. Model swap failures are probably invisible in the UI

Severity: Medium

File:
- [app/src/main/kotlin/com/openclaw/ghostcrab/ui/model/ModelManagerViewModel.kt](/C:/Users/steve/projects/GhostCrab/app/src/main/kotlin/com/openclaw/ghostcrab/ui/model/ModelManagerViewModel.kt:94)

On failure, the ViewModel emits `Error(...)` and immediately overwrites it with `Ready(...)` — both assignments are synchronous with no suspension point between them. Because `StateFlow` only delivers the final value, the `Error` state is guaranteed not to render.

Recommended update:
- keep the screen in `Ready`
- surface the failure as a snackbar or one-shot UI event
- reserve `Error` for initial load failure only

## Missed Updates

### 1. Detekt is documented but not actually wired

Files:
- [README.md](/C:/Users/steve/projects/GhostCrab/README.md:62)
- [build.gradle.kts](/C:/Users/steve/projects/GhostCrab/build.gradle.kts:1)
- [app/build.gradle.kts](/C:/Users/steve/projects/GhostCrab/app/build.gradle.kts:1)

The repo says `./gradlew lint detekt` is part of the workflow, but the app module does not apply the Detekt plugin. Running `./gradlew detekt --no-configuration-cache` currently fails because the task does not exist.

Recommended update:
- apply the Detekt plugin in the app module
- point it at `config/detekt.yml`
- add it to CI

### 2. CI only runs unit tests

File:
- [.github/workflows/ci.yml](/C:/Users/steve/projects/GhostCrab/.github/workflows/ci.yml:1)

CI currently runs unit tests only. That is not enough for the app state it has reached.

Recommended update:
- add `lint`
- add `detekt` once wired
- optionally add debug assemble and dependency verification

### 3. Documentation is stale

Files:
- [README.md](/C:/Users/steve/projects/GhostCrab/README.md:24)
- [README.md](/C:/Users/steve/projects/GhostCrab/README.md:113)
- [IMPLEMENTATION_PLAN.md](/C:/Users/steve/projects/GhostCrab/IMPLEMENTATION_PLAN.md:47)

README still says the model manager is in progress, while the implementation plan marks Phase 7 done.

Recommended update:
- sync README phase/status text with the implementation plan
- add a short "Current milestone" section that is easier to keep up to date

### 4. Config save flow has fragile ETag state

File:
- [app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/ConfigRepositoryImpl.kt](/C:/Users/steve/projects/GhostCrab/app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/ConfigRepositoryImpl.kt:19)

`lastEtag` is refreshed on `getConfig()` only. The current flow is safe because the screen re-fetches after save, but the repository contract is fragile if a future screen does consecutive writes without reloading first.

Recommended update:
- refresh the stored ETag from response headers after a successful update if the API supports it
- or document that every write must be followed by a read before further mutation

### 5. Hardening work is ready now

The app has moved past skeleton status. The next missed updates are not new features first, but hardening:
- clean up lint warnings
- remove unused resources
- verify icon duplication issues
- formalize cleartext restrictions
- add startup/integration tests for Koin and navigation
- add release-oriented crash handling and empty-state polish

## Phase 6 and 7 Remaining Work

### Phase 6 — Config Editor

The commit exists, but the DI graph was never fixed. `ConfigRepository` resolves to `TODO("Phase 6")` at runtime, so the config editor screen is unreachable and none of the acceptance criteria have been verified against a real gateway.

Blocking fix:
- Remove `single<ConfigRepository> { TODO("Phase 6") }` from `DomainModule`. `DataModule` already provides `ConfigRepositoryImpl`.

Acceptance criteria to re-verify after the DI fix:
- Round-trip edit on `gateway.http.port`: change → save → re-read → value matches.
- Invalid value (e.g., port 99999) surfaces a field-level error without losing other edits.
- Unknown config section appears in the raw JSON editor and round-trips losslessly.
- Concurrent edit by another client is detected from the re-read diff and surfaces "Server values changed since you opened this section. [Reload]".

Also needed:
- Refresh the stored ETag from response headers after a successful `updateConfig` if the API returns one — or add explicit KDoc to `ConfigRepositoryImpl` stating that every write must be followed by a read before further mutation.

### Phase 7 — Model Manager

Same root cause: `single<ModelRepository> { ModelRepositoryStub() }` in `DomainModule` overrides `ModelRepositoryImpl` from `DataModule`. All model data on-screen is stub data, not gateway data.

Blocking fix:
- Remove `single<ModelRepository> { ModelRepositoryStub() }` from `DomainModule`.

Acceptance criteria to re-verify after the DI fix:
- Active model is visually distinct from the list.
- Swap reflects on the Dashboard within one health-poll cycle.
- Provider auth failures (gateway-side) surface as a per-model warning chip.

Note: Keep `single<AIRecommendationService> { TODO("Phase 8") }` in `DomainModule` — Phase 8 is next sprint.

## Verification Summary

Commands run:
- `./gradlew testDebugUnitTest --no-configuration-cache`
- `./gradlew lint --no-configuration-cache`
- `./gradlew detekt --no-configuration-cache`

Observed result:
- unit tests passed
- lint passed, but the generated report shows 75 warnings
- detekt failed because no `detekt` task is currently configured

## UI Rework Plan

## Goal

Do not do a cosmetic screen-by-screen pass. Rebuild the product around a clearer information hierarchy and a stronger admin-console identity.

The existing brand direction is usable:
- dark abyss background
- cyan operational accent
- glass surfaces for secondary panels

What is missing is structure, emphasis, and navigation coherence.

## Proposed Visual Direction

Design the app as an "abyssal operations console" rather than a generic Material utility app.

Characteristics:
- denser, more deliberate information layout
- stronger separation between primary panels and secondary glass cards
- clear live-status language
- restrained motion only where it communicates state
- fewer repeated card stacks, more intentional dashboard and workspace layouts

## Information Architecture Rework

### 1. Split the app into two modes

Mode 1: Pre-connection
- connection picker
- scan
- manual entry
- onboarding

Mode 2: Connected shell
- overview
- config
- models
- AI recommendations
- settings/profile

Once connected, the user should land in a persistent shell rather than bouncing between isolated screens with back-navigation semantics.

### 2. Create a connected app shell

Primary destinations:
- `Overview`
- `Config`
- `Models`
- `AI`
- `Settings`

Recommended navigation pattern:
- bottom bar for phone ergonomics
- or compact rail on larger devices/foldables later

Persistent shell benefits:
- clearer orientation
- easier return to dashboard
- fewer awkward pop/back flows
- consistent place for connection state and security posture

## Screen Rework Priorities

### Phase A: Foundations

Build these first:
- connected app shell
- new spacing rhythm
- primary panel component
- secondary glass panel component
- status badge system
- empty/error/loading state system

Outcome:
- every later screen gets rebuilt on better primitives instead of ad-hoc cards

### Phase B: Dashboard becomes Overview

Current issue:
- the dashboard is functionally correct but visually flat
- everything has equal weight

Rework:
- hero header with gateway name, environment, security posture
- prominent health card with last success, degraded state, and recovery actions
- active model spotlight
- capability chips grouped by type
- quick actions as primary action row
- recent/admin actions section later if needed

Key rule:
- above-the-fold should answer:
  - what gateway am I on
  - is it healthy
  - is it secure
  - what model is active

### Phase C: Config Editor

Current issue:
- the screen is technically capable but feels like a stacked expandable form list

Rework:
- sticky section index or segmented header for major config sections
- more explicit dirty-state handling
- persistent action footer when a section is modified
- search/filter for section names and field paths
- clearer distinction between typed sections and raw JSON fallback
- diff sheet restyled as a review step, not just a modal interruption

Target feel:
- configuration workspace, not accordion list

### Phase D: Model Manager

Current issue:
- list works, but model state is not prioritized enough

Rework:
- spotlight the active model at top
- group remaining models by provider
- add stronger status semantics for auth-error, unavailable, and healthy
- move details into a more integrated detail panel or anchored sheet
- add search and filter if model count grows

Target feel:
- switching models should feel deliberate and operational

### Phase E: Connection and Onboarding

Current issue:
- pre-connection screens are serviceable but generic

Rework:
- connection picker becomes a "gateway roster"
- saved gateways show security state, last connected time, and connection affordances
- scan screen should visually communicate discovery activity more strongly
- onboarding should keep its clarity but gain a stronger narrative visual rhythm

Target feel:
- first-run should teach confidence, not just forms and buttons

## Component System to Add

Create shared primitives before broad UI changes:
- `PrimaryPanel`
- `SecondaryGlassPanel`
- `StatusBadge`
- `MetricTile`
- `SectionHeader`
- `EmptyStatePanel`
- `ErrorStatePanel`
- `ActionRow`
- `StickySaveBar`
- `SecurityPostureBanner`

This prevents each screen from inventing its own structure.

## UX Improvements to Include in the Rework

- Connection status should always be visible in the connected shell.
- HTTP and no-auth posture should be shown as persistent contextual status, not only inline warnings.
- Actions that affect the whole gateway should feel heavier than local UI actions.
- Recoverable failures should use snackbars or inline status regions, not full-screen error replacement unless the screen truly cannot function.
- Save/swap/reconnect flows should preserve orientation and avoid jarring navigation resets.

## Implementation Order

Recommended execution order:

1. Fix DomainModule:
   - 1a. Remove `ConfigRepository` and `ModelRepository` bindings from `DomainModule`.
   - 1b. Run `./gradlew assembleDebug` — confirm Koin graph resolves cleanly.
   - 1c. Re-verify Phase 6 acceptance criteria against a real gateway.
   - 1d. Re-verify Phase 7 acceptance criteria against a real gateway.
   - 1e. Fix `safeRequest` `CancellationException` + `SerializationException` handling.
   - 1f. Fix onboarding `SharedFlow(replay=0)` event delivery.
   - 1g. Fix dashboard health checker client lifecycle.
2. Wire static analysis and CI hardening.
3. Build the connected shell and new shared components.
4. Rework dashboard into overview.
5. Rework model manager.
6. Rework config editor.
7. Rework connection and onboarding flows.
8. Finish Phase 8 and Phase 9 features inside the new shell.

## Suggested Next Sprint

If you want the highest leverage next sprint, do this:

0. Fix `DomainModule` — remove `ConfigRepository` and `ModelRepository` stubs (unblocks the entire connected feature surface).
1. Verify Phase 6 and Phase 7 acceptance criteria against a real gateway now that DI resolves correctly.
2. Fix `safeRequest` cancellation/error mapping.
3. Fix onboarding event delivery.
4. Fix dashboard health client lifecycle.
5. Wire Detekt and expand CI to `test + lint + detekt`.
6. Build a connected-shell prototype with `Overview`, `Config`, `Models`, and `Settings`.

That sequence reduces risk before you invest in UI work, and it gives the rework a stable foundation.
