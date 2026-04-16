# ADR-001: Kotlin + Jetpack Compose over React Native

**Status:** Accepted  
**Date:** 2026-04-14

## Context

GhostCrab is a configuration and administration client for technically literate users running their own OpenClaw Gateway. Performance, platform integration (NsdManager, EncryptedSharedPreferences, Android Keystore), and long-term maintainability were the primary selection criteria. A cross-platform option (React Native, Flutter) was evaluated.

## Decision

Use Kotlin 2.0 + Jetpack Compose + Android-native APIs exclusively.

## Reasons

- Direct access to `NsdManager` (mDNS LAN discovery) without a bridge layer.
- `EncryptedSharedPreferences` + Android Keystore integration is first-class in Kotlin; bridges add attack surface.
- Compose's state-driven model maps cleanly onto the sealed-state architecture.
- Single-platform scope aligns with v1.0 capacity (iOS is out-of-scope per `IMPLEMENTATION_PLAN.md §1.6`).

## Consequences

- iOS support requires a separate project. Documented as out-of-scope for v1.0.
- No shared UI code with any future web front-end.
