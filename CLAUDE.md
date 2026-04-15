# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

GhostCrab — Android-native remote client for OpenClaw Gateway.  
Stack: **Kotlin 2.0+ · Jetpack Compose · Ktor · Koin · NsdManager · DataStore + EncryptedSharedPreferences**  
Target SDK 35, min SDK 26 (Android 8.0), JVM 17, `compileSdk = 35`.

## Commands

```bash
./gradlew assembleDebug              # build
./gradlew testDebugUnitTest          # unit tests
./gradlew connectedDebugAndroidTest  # instrumented tests
./gradlew lint detekt                # lint
./gradlew installDebug               # install on device/emulator
```

Run a single test class:
```bash
./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.GatewayConnectionManagerImplTest"
```

## Architecture

Single-activity Compose app. Strict layering — no skipping layers:

```
UI (Compose)  →  ViewModel (StateFlow)  →  Domain (interfaces)  →  Data (Ktor / NSD / DataStore)
```

Package root: `app/src/main/kotlin/com/openclaw/ghostcrab/`

| Package | Purpose |
|---------|---------|
| `di/` | Koin modules: `AppModule`, `DataModule`, `DomainModule`, `UiModule` |
| `domain/model/` | Immutable data classes / sealed interfaces (wire types are `@Serializable`) |
| `domain/exception/` | Sealed `GatewayException` hierarchy — every leaf has `isRetryable: Boolean` |
| `domain/repository/` | Frozen interfaces — do **not** change signatures without a documented ADR |
| `data/api/` | `OpenClawApiClient` (Ktor) + DTOs |
| `data/discovery/` | `NsdDiscoveryServiceImpl` |
| `data/storage/` | `ConnectionProfileStore` (DataStore metadata + EncryptedSharedPreferences tokens) |
| `data/impl/` | Repository implementations |
| `ui/theme/` | `BrandTokens`, `Color`, `Type`, `Shape`, `Spacing`, `GhostCrabTheme` |
| `ui/components/` | Shared composables: `GlassSurface`, `SecurityBanner`, `ConnectionStatusBar`, etc. |
| `ui/navigation/` | `Routes` sealed class, `NavGraph` composable (type-safe Compose Navigation) |

## Key Constraints

- **Frozen contracts**: Interfaces in `domain/repository/` are the v1.0 API surface. Implementations change; signatures don't.
- **Type safety**: No `Any`, no unchecked casts, no swallowed exceptions. Use sealed hierarchies.
- **All public APIs**: KDoc with `@param`, `@return`, `@throws`, and edge-case notes.
- **Secrets**: Never in source. Use `local.properties` / `BuildConfig`. Tokens stored in `EncryptedSharedPreferences` (AES256_GCM_SPEC).
- **Logging**: Strip `Authorization` headers from Ktor logs. Verify with `adb logcat | grep -i bearer`.
- **No work in `GhostCrab (1)/` folder.** Canonical workspace is `GhostCrab/`.

## Brand / Theme Rules

All colors and fonts come from `ui/theme/BrandTokens.kt` — no hardcoded hex values elsewhere.

| Token | Value | Use |
|-------|-------|-----|
| `colorAbyss` | `#0F1115` | Default background |
| `colorCyanPrimary` | `#5BE9FF` | Primary actions, connected state |
| `colorCyanPulse` | `#7BD8FF` | Scanning / mDNS pulse |
| `colorAmberWarn` | `#E0A458` | Disconnected / HTTP-mode banner |
| `colorCrimsonError` | `#FF4D6D` | Auth / validation errors |
| `colorGlass` | `rgba(255,255,255,0.06)` | `GlassSurface` overlay |

- Surfaces use `GlassSurface` composable (6% white-alpha over `colorAbyss`). No solid gray cards.
- HTTP-mode: amber banner. HTTP + `auth: none`: amber background + crimson outline.
- Error messages are explicit: show exact URL, HTTP status code, and exception class. No "Oops" copy.
- Animations only for state transitions and active-scan pulse. No decorative motion.
- IP addresses, ports, JSON, CLI output → JetBrains Mono. UI text → Inter.

## Implementation Phases

Progress tracked in `IMPLEMENTATION_PLAN.md`. Each phase is self-contained; read §0 + §1 + the target phase before starting. Always update the Progress Ledger at phase end.

Current status: **Phase 0 not started** (no code exists yet).

## Out of Scope (v1.0)

WebSocket streaming · offline config cache · iOS · agent runtime on-device · provider-API keys on-device · multi-active-connection · Play Billing.
