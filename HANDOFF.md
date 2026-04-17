# GhostCrab Dev Handoff

**Date:** 2026-04-16  
**Branch:** main  
**Build status:** CI passing — lint fixed, all unit tests green

---

## What Was Done

### Phase 11 — QR Connect + Splash (completed)

- `QrScanScreen` — CameraX viewfinder + ML Kit QR decode, camera permission flow with permanent-deny dialog, cyan `ViewfinderOverlay`
- `QrScanViewModel` — permission state machine, barcode event channel
- `tunnel-qr` Docker helper (`docker/tunnel-qr/`) — Python service reads `cloudflared` stdout for the quick-tunnel URL, serves a QR code page at LAN port 19999
- GhostCrab logo in `ConnectionPickerScreen` TopAppBar; QR hero empty state on first launch
- Animated splash screen — crab `VectorDrawable` with scale+fade `AnimatedVectorDrawable`, branding on API 31+
- `docs/TUNNEL_SETUP.md` — setup guide for Docker Compose and direct install paths

### Phase 10 — Production Hardening (completed)

#### HIGH severity fixes
| ID | Fix |
|----|-----|
| H1 | `ConfigRepositoryImpl.lastEtag` — replaced `var` with `AtomicReference<String?>` |
| H2 | `ConnectionProfileStore.encryptedPrefs` — replaced per-call Keystore init with `by lazy` |
| H3 | `GatewayConnectionManagerImpl.settingsRepository` — nullable default removed; `AlwaysBlockCleartextSettingsRepository` sentinel is the non-nullable default |
| H4 | `NsdDiscoveryServiceImpl.activeChannel` — added `@Volatile` |
| H5 | `safeRequest()` in `OpenClawApiClient` — collapsed explicit re-throws to `catch (e: GatewayException)` — fixes `ConfigValidationException` being swallowed as `GatewayUnreachableException` |

Also created `CleartextPublicIpInterceptor` (OkHttp Interceptor) installed in every `buildHttpClient` call.

#### IMPORTANT severity fixes
| ID | Fix |
|----|-----|
| I1 | Deleted `Routes.kt` (dead code) |
| I2 | Deleted `ModelRepositoryStub.kt` (superseded in Phase 7) |
| I3 | All 5 ViewModels — `_state.update {}` replaces read-modify-write |
| I4 | `DashboardViewModel` poll loop — documented asymmetric delay |
| I5 | `OnboardingRepositoryImpl.stepFromName()` — documented silent `Welcome` fallback |
| I6 | `AIRecommendationViewModel.applySelectedChanges()` — partial-failure reports "Applied N of M" |
| I7 | Health-check lambda moved from `UiModule` to `DataModule.gatewayHealthCheck()` |
| I8 | `HealthSnapshot.isStale` — stored `Boolean` from ViewModel instead of live `currentTimeMillis()` |
| I9 | `QrScanScreen` — `LaunchedEffect` keys changed from `Unit` to `"permission"` / `viewModel` |
| I10 | `ManualEntryViewModel.connect()` — TOCTOU on `connectionState.value` documented |

#### CI fixes (this session)
- GH Actions bumped to Node.js 24 native (`checkout@v5`, `setup-java@v5`, `upload-artifact@v6`)
- `QrScanScreen` lint — `@OptIn` and `@androidx.annotation.OptIn` both fail for `ExperimentalGetImage`; fixed with `@SuppressLint("UnsafeOptInUsageError")` on `CameraPreview` + `analyzeProxy`
- R8: `-dontwarn org.slf4j.**` for Ktor logging in release builds
- `compileSdk`/`buildTools` pinned to 35; test executor heap set to `-Xmx1g`
- `.gitignore` updated

#### New unit tests (+65)
- `OpenClawApiClientTest` — 28 tests via MockEngine
- `AIRecommendationServiceImplTest` — 10
- `ModelRepositoryImplTest` — 9
- `ConnectionProfileRepositoryImplTest` — 9
- `GatewaySectionTest` — 8
- `CleartextPublicIpInterceptorTest` — 9

---

## Audit Items Still Open

From `docs/audit-2026-04-16.md` — LOW severity (not blocking v1.0):

| ID | Item |
|----|------|
| L1 | `setActiveModel` builds JSON via string interpolation — use `buildJsonObject` |
| L2 | `DomainModule` is empty — delete it or move domain bindings into it |
| L3 | `OnboardingViewModel.generateToken()` lives in ViewModel companion — move to `domain/` |
| L4 | `CleartextPublicIpInterceptor.isPublicIpLiteral` calls `InetAddress.getByName()` — possible DNS on some Android versions |
| L5 | `PrivacySafeUncaughtExceptionHandler` passes unsanitized throwable to delegate — document assumption (no crash SDK installed) |
| L6 | `NsdDiscoveryServiceImpl` multicast lock acquire not wrapped in `runCatching` |
| L7 | `ScanViewModel.onCleared()` missing `super.onCleared()` |
| L8 | `STALE_THRESHOLD_MS` and `POLL_INTERVAL_MS` in separate companions with no cross-reference |

Testing gaps (not blocking v1.0 but worth tracking):

| ID | Item |
|----|------|
| T1 | `ConfigEditorViewModelTest` — missing entirely (complex 412/422 paths untested) |
| T2 | `GatewayConnectionManagerImplTest` — concurrent `connect()` not tested |
| T3 | `CleartextPublicIpInterceptor` — end-to-end OkHttp wiring not tested |
| T4 | `OnboardingViewModelTest` — `back()` from Welcome + `skip()` from mid-flow missing |
| T5 | `ConnectionProfileStore` — no tests for EncryptedSharedPreferences failure path |

---

## Architecture State

```
UI  →  ViewModel (StateFlow)  →  Domain (interfaces)  →  Data (Ktor / NSD / DataStore)
```

All ViewModels use `_state.update {}`. No raw read-modify-write remains. Layer boundaries clean. `GatewayConnectionManagerImpl` enforces cleartext policy via Koin-injected `settingsRepository`.

---

## What's Next

1. **Run instrumented tests on device** — `./gradlew connectedDebugAndroidTest` with a connected emulator/device. Phase 12 added 3 test classes (13 tests total):
   - `ConnectionProfileStoreInstrumentedTest` — 8 tests, real Keystore/EncryptedSharedPreferences round-trip
   - `NsdDiscoveryServiceInstrumentedTest` — 2 tests, real `NsdManager` register+discover (flaky on host-only emulator; use physical device)
   - `ConnectionPickerScreenTest` — 3 Compose UI smoke tests with fake VM (no Koin)
2. **ProGuard/R8 verification** — confirm release AAB from CI runs without crashes (Ktor serialization, Koin reflection).
3. **Out-of-scope for v1.0** (CLAUDE.md): WebSocket streaming, offline config cache, multi-connection, Play Billing.

---

## Key Files

| File | Notes |
|------|-------|
| `data/api/OpenClawApiClient.kt` | HTTP client + exception mapping; `forTest()` for MockEngine |
| `data/api/CleartextPublicIpInterceptor.kt` | OkHttp interceptor; cleartext policy |
| `data/impl/GatewayConnectionManagerImpl.kt` | Connection state machine; `AlwaysBlockCleartextSettingsRepository` sentinel |
| `di/DataModule.kt` | All singletons + `gatewayHealthCheck()` |
| `di/UiModule.kt` | ViewModels; no data-layer imports |
| `ui/connection/QrScanScreen.kt` | CameraX + ML Kit QR scan; `@SuppressLint("UnsafeOptInUsageError")` |
| `ui/dashboard/DashboardUiState.kt` | `HealthSnapshot.isStale` is stored `Boolean` |
| `docs/audit-2026-04-16.md` | Full audit — reference for remaining LOW/T items |
| `.github/workflows/ci.yml` | Unit tests + release AAB smoke build |
