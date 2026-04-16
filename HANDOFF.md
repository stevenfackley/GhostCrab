# GhostCrab Dev Handoff

**Date:** 2026-04-16  
**Branch:** main  
**Build status:** passing (all unit tests green)

---

## What Was Done

### Phase 10 — Production Hardening (completed this session)

#### HIGH severity fixes
| ID | Fix |
|----|-----|
| H1 | `ConfigRepositoryImpl.lastEtag` — replaced `var` with `AtomicReference<String?>` |
| H2 | `ConnectionProfileStore.encryptedPrefs` — replaced per-call Keystore init with `by lazy` |
| H3 | `GatewayConnectionManagerImpl.settingsRepository` — nullable default removed; non-nullable `AlwaysBlockCleartextSettingsRepository` sentinel is the default |
| H4 | `NsdDiscoveryServiceImpl.activeChannel` — added `@Volatile` |
| H5 | `safeRequest()` in `OpenClawApiClient` — collapsed `GatewayAuthException`/`GatewayApiException` re-throws to single `catch (e: GatewayException)` — fixes `ConfigValidationException` being swallowed |

Also created `CleartextPublicIpInterceptor` (OkHttp Interceptor) installed in every `buildHttpClient` call. Enforces `allowCleartextPublicIPs` setting at the HTTP layer.

#### IMPORTANT severity fixes
| ID | Fix |
|----|-----|
| I1 | Deleted `Routes.kt` (dead code — NavGraph uses string literals) |
| I2 | Deleted `ModelRepositoryStub.kt` (superseded in Phase 7) |
| I3 | All 5 ViewModels — replaced `_state.value = ...` read-modify-write with `_state.update {}` for atomic CAS |
| I4 | `DashboardViewModel` poll loop — added comment explaining asymmetric delay (degraded break exits without waiting) |
| I5 | `OnboardingRepositoryImpl.stepFromName()` — documented `else → Welcome` silent fallback |
| I6 | `AIRecommendationViewModel.applySelectedChanges()` — partial-failure error now reports "Applied N of M changes; stopped at change K" |
| I7 | Moved `OpenClawApiClient` instantiation from `UiModule` into `DataModule` as `gatewayHealthCheck()` top-level function; `UiModule` now uses `::gatewayHealthCheck` method reference |
| I8 | `HealthSnapshot.isStale` — removed computed getter calling `System.currentTimeMillis()`; now stored `Boolean` computed by `DashboardViewModel` at emission time (Compose stability fix) |
| I9 | `QrScanScreen` — two `LaunchedEffect(Unit)` given distinct keys: `"permission"` and `viewModel` |
| I10 | `ManualEntryViewModel.connect()` — documented TOCTOU on `connectionState.value` read after `connect()` returns |

#### Build / CI fixes
- `compileSdk` and `buildTools` pinned to 35 (was accidentally bumped to 36/37)
- `testOptions.unitTests.all { it.jvmArgs("-Xmx1g") }` — fixes JVM OOM in test executor
- `.gitignore` updated: `build/`, `hs_err_pid*.log`, `replay_pid*.log`, `docs/superpowers/`
- Added `release-build` CI job: decodes keystore from `KEYSTORE_BASE64` secret, runs `bundleRelease`, uploads AAB artifact (14-day retention)
- Added `import com.openclaw.ghostcrab.domain.exception.GatewayException` to `OpenClawApiClient.kt` (was missing despite the catch clause)

#### New unit tests added
- `OpenClawApiClientTest` — 28 tests via MockEngine + `forTest()` factory
- `AIRecommendationServiceImplTest` — 10 tests
- `ModelRepositoryImplTest` — 9 tests
- `ConnectionProfileRepositoryImplTest` — 9 tests
- `GatewaySectionTest` — 8 tests
- `CleartextPublicIpInterceptorTest` — 9 tests

---

## Architecture State

Strict 4-layer Compose app. Layer boundaries are clean as of this session.

```
UI  →  ViewModel (StateFlow)  →  Domain (interfaces)  →  Data (Ktor / NSD / DataStore)
```

All ViewModels use `_state.update {}` for read-modify-write. No raw `_state.value = ...` read-modify-write patterns remain.

`GatewayConnectionManagerImpl` is now wired with `settingsRepository` from Koin DI (`DataModule`); the cleartext policy is enforced on every HTTP connection.

---

## What's Next

Per `IMPLEMENTATION_PLAN.md`, Phase 10 hardening is done. Remaining pre-v1.0 work:

1. **Instrumented tests** — none exist yet. ConnectionProfileStore (DataStore + EncryptedSharedPreferences), NsdDiscoveryServiceImpl, and end-to-end Compose UI tests are candidates.
2. **ProGuard/R8 rules** — verify release AAB from CI doesn't have runtime crashes from missing keep rules (especially for Ktor serialization and Koin).
3. **Out-of-scope items** (documented in CLAUDE.md): WebSocket streaming, offline config cache, multi-connection, Play Billing.

---

## Key Files

| File | Notes |
|------|-------|
| `data/api/OpenClawApiClient.kt` | HTTP client + all exception mapping; `forTest()` factory for MockEngine |
| `data/api/CleartextPublicIpInterceptor.kt` | OkHttp interceptor; enforces cleartext policy |
| `data/impl/GatewayConnectionManagerImpl.kt` | Connection state machine; `AlwaysBlockCleartextSettingsRepository` sentinel |
| `di/DataModule.kt` | All singletons + `gatewayHealthCheck()` top-level function |
| `di/UiModule.kt` | ViewModels; no data-layer imports |
| `ui/dashboard/DashboardUiState.kt` | `HealthSnapshot.isStale` is now a stored `Boolean` |
| `.github/workflows/ci.yml` | Unit tests + release build jobs |
