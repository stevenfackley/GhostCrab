# ADR-005: Additive Methods on Frozen Repository Interfaces

**Status:** Accepted  
**Date:** 2026-10-14

## Context

Interfaces in `domain/repository/` are the v1.0 API surface and are frozen. v0.2.0 needed two capabilities the v1.0 interfaces lack: wiping all profile data (including undecodable data) and a single `/health` call on the live session client.

## Decision

Frozen interfaces may gain ADDITIVE members without a new ADR each. Existing signatures never change. Each addition is marked `Additive (v1.1)` in its KDoc (Android) or doc comment (iOS).

The rule covers both platforms: the iOS protocols in `ios/GhostCrab/Domain/Repository/` are frozen the same way.

Instances on `refactor/finish-dev-plan` (PR #125, Android):

- `ConnectionProfileRepository.clearAll()`
- `GatewayHealthChecker` (new interface, implemented by `GatewayConnectionManagerImpl`)

Instances on `feat/ios-finish` (PR #127, iOS):

- `ConnectionProfileRepository.clearAll()` (Swift protocol; implemented by `ConnectionProfileRepositoryImpl` over `ProfileStore.clearAll()`, used by Settings → "Clear all saved gateways", mirroring Android's `SettingsViewModel.confirmClearAllProfiles`)

## Reasons

- Additive members do not break existing callers or v1.0 contracts.
- An ADR per method is overhead; this one records the rule and the current instances.

## Consequences

- Changing or removing an existing member still requires a new ADR.
- Every additive member carries the `Additive (v1.1)` marker in its KDoc or doc comment so it can be audited with a grep.
