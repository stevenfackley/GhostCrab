<p align="center">
  <img src="docs/feature_graphic_1024x500.png" alt="GhostCrab — Remote Client for OpenClaw Gateway" width="100%">
</p>

<p align="center">
  <a href="https://github.com/stevenfackley/GhostCrab/actions/workflows/ci.yml"><img src="https://github.com/stevenfackley/GhostCrab/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-brightgreen" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/iOS-18%2B-007AFF" alt="iOS 18+">
  <img src="https://img.shields.io/badge/Mac%20Catalyst-14%2B-1D1D1F" alt="Mac Catalyst 14+">
  <img src="https://img.shields.io/badge/Kotlin-2.0-7F52FF" alt="Kotlin 2.0">
  <img src="https://img.shields.io/badge/Swift-6-FA7343" alt="Swift 6">
  <img src="https://img.shields.io/badge/version-0.2.0-blue" alt="v0.2.0">
</p>

**GhostCrab** is the native iOS, iPadOS, Mac Catalyst, and Android client for [OpenClaw Gateway](https://openclaw.io) — the open-source AI agent and LLM orchestration platform. Point it at any reachable gateway on your LAN or over the internet and get full remote control from your phone, tablet, or Mac.

It is not a consumer AI chat app. It is a configuration and administration client for people who run their own gateway and want first-class mobile + desktop access to their self-hosted AI infrastructure.

## Platforms

| Platform | Min OS | Code | Distribution |
|---|---|---|---|
| Android | 8.0 (API 26) | Kotlin + Jetpack Compose — `app/` | Play Store |
| iOS / iPadOS | 18.0 | Swift 6 + SwiftUI — `ios/` | TestFlight → App Store |
| macOS (Catalyst) | 14.0 Sonoma | shared with iOS via Catalyst | TestFlight (Mac) |

Both apps share the same `Domain/Repository/` contract surface — frozen v1.0 interfaces, same screen-by-screen feature parity. See [`docs/superpowers/specs/2026-05-28-ios-app-design.md`](docs/superpowers/specs/2026-05-28-ios-app-design.md).

---

## Features

- **Auto-discovery** — finds OpenClaw gateways on your local Wi-Fi via mDNS (no IP hunting required)
- **Manual connect** — enter any `http(s)://` or `ws(s)://` URL + optional gateway token for remote access; a new phone is approved once on the gateway host with `openclaw devices approve <id>`
- **Pairing** — scan a `ghostcrab://pair` QR code or open a `ghostcrab://pair` link to prefill the connect form (you still tap Connect)
- **Config editor** — form-based editor for `openclaw.json` with typed fields, validation, and a diff-before-save sheet
- **Dashboard** — live gateway health, active model, version, and capability readout; Configure and Models appear only when the session's scopes allow them
- **Model manager** — view all configured models, swap the active one
- **Skills** — list, install and disable skills (`skills.status` / `skills.install` / `skills.update`); debug builds only for now (`SKILLS_INSTALL_ENABLED` is off in release), and offered only when the session's scopes allow it
- **AI recommendations** — hidden for now: OpenClaw has no recommendation API (ADR-0007)
- **Secure storage** — gateway and device tokens, and the device's Ed25519 key, encrypted with AES-256-GCM via Android Keystore; never logged
- **Security banners** — explicit amber/crimson warnings for HTTP and no-auth connections; no silent risks
- **Onboarding walkthrough** — step-by-step guide to install, start, and connect to your first gateway

---

## Requirements

| | |
|---|---|
| Android | 8.0+ (API 26) |
| Gateway | OpenClaw Gateway — reachable from your device (LAN or URL) |

---

## Build

```bash
# Debug APK
./gradlew assembleDebug

# Install on connected device / emulator
./gradlew installDebug
```

---

## Release Build

```bash
# Signed release APK (requires local.properties with signing creds — see docs/RELEASE_SIGNING.md)
./gradlew :app:assembleRelease --no-configuration-cache

# Signed AAB for Play Store
./gradlew :app:bundleRelease --no-configuration-cache
```

See [`docs/RELEASE_SIGNING.md`](docs/RELEASE_SIGNING.md) for keystore location, CI wiring, and backup guidance.

---

## Development

```bash
# Unit tests
./gradlew testDebugUnitTest --no-configuration-cache

# Instrumented tests (requires connected device)
./gradlew connectedDebugAndroidTest

# Lint + static analysis
./gradlew lint detekt

# Single test class
./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.GatewayConnectionManagerImplTest"
```

---

## Architecture

Single-activity Compose app. Strict layer separation — nothing skips a layer:

```
UI (Compose)  →  ViewModel (StateFlow)  →  Domain (interfaces)  →  Data (Ktor / NSD / DataStore)
```

| Layer | Key classes |
|-------|-------------|
| **UI** | `NavGraph`, screen composables, `GhostCrabTheme`, shared components (`GlassSurface`, `CodeBlock`, `SecurityBanner`) |
| **ViewModel** | One VM per screen, `StateFlow<UiState>`, `SharedFlow` for one-shot events |
| **Domain** | Frozen interfaces in `domain/repository/`, immutable models in `domain/model/`, sealed `GatewayException` hierarchy |
| **Data** | `OpenClawApiClient` (Ktor + OkHttp), `NsdDiscoveryServiceImpl`, `ConnectionProfileStore` (DataStore + EncryptedSharedPreferences), repository impls |
| **DI** | Koin — `AppModule`, `DataModule`, `UiModule` |

Domain interfaces are the v1.0 API surface and are **frozen** — implementations change, signatures don't.

---

## Privacy

- No analytics, no ads, no cloud backend
- Connection profiles stored locally via DataStore; gateway/device tokens stored in EncryptedSharedPreferences (AES-256-GCM, Android Keystore)
- Nothing is sent to the developer
- `Authorization` headers are stripped from all HTTP logs — verify with `adb logcat | grep -i bearer`

See [`docs/PRIVACY_POLICY.md`](docs/PRIVACY_POLICY.md) for the full policy.

---

## Implementation Status

| Phase | Feature | Status |
|-------|---------|--------|
| 0 | Scaffold, theme, DI skeleton | ✅ Done |
| 1 | Domain contracts | ✅ Done |
| 2 | Connection: Manual entry + auth probe | ✅ Done |
| 3 | Discovery: NsdManager + scan UI | ✅ Done |
| 4 | Dashboard, health polling, security banners | ✅ Done |
| 5 | Onboarding walkthrough | ✅ Done |
| 6 | Config editor (forms over `openclaw.json`) | ✅ Done |
| 7 | Model manager | ✅ Done |
| 8 | AI recommendations (gateway-proxied) | ⏸ Disabled: no upstream API (ADR-0007) |
| 9 | Settings, profile management, about | ✅ Done |
| 10 | Hardening, release | ✅ Done |
| 11 | QR connect, splash | ✅ Done |
| 12 | Skills install (WebSocket) | 🟡 On-device QA pending |
| 13 | R8 release build verification | ✅ Done |
| 14 | Release signing | ✅ Done |

---

## Contributing

Bug reports and pull requests are welcome. Please read `docs/IMPLEMENTATION_PLAN.md` before starting significant work — it describes the phased build plan and frozen contracts.

---

## License

Apache 2.0 — see `LICENSE`.
