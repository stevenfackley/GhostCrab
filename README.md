# GhostCrab

Android remote client for OpenClaw Gateway.

## Requirements

- Android 8.0+ (API 26)
- OpenClaw Gateway reachable on LAN or via URL

## Build

```bash
./gradlew assembleDebug
./gradlew installDebug
```

## Test

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
./gradlew lint detekt
```

## Architecture

Single-activity Compose app. Layered: UI → ViewModel → Domain → Data.
See `IMPLEMENTATION_PLAN.md` for phased build plan.
